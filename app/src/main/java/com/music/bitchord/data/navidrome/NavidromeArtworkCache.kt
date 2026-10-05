package com.music.bitchord.data.navidrome

import android.content.Context
import com.music.bitchord.data.Http
import com.music.bitchord.data.cache.ImageCacheBudget
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** Persistent authenticated artwork cache shared by Coil and Android media surfaces. */
object NavidromeArtworkCache {
    private const val LRU_TOUCH_INTERVAL_MS = 6L * 60 * 60 * 1000
    private val keyLocks = Array(256) { Any() }
    private val heldBytes = AtomicLong(-1L)
    private val maxBytes = AtomicLong(
        ImageCacheBudget.staticArtworkBytes(AppSettings.DEFAULT_IMAGE_CACHE_LIMIT_BYTES),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var root: File? = null
    @Volatile private var observingLimit = false

    fun init(context: Context) {
        val directory = directory(context)
        maxBytes.set(ImageCacheBudget.staticArtworkBytes(AppSettings.imageCacheLimitBytes.value))
        trim(directory, keep = null)
        synchronized(this) {
            if (observingLimit) return
            observingLimit = true
        }
        scope.launch {
            AppSettings.imageCacheLimitBytes.collectLatest { limit ->
                maxBytes.set(ImageCacheBudget.staticArtworkBytes(limit))
                trim(directory, keep = null)
            }
        }
    }

    fun getOrFetch(context: Context, coverId: String, requestedSize: Int?): File {
        val cacheKey = cacheKey(coverId, requestedSize)
        val directory = directory(context)
        val destination = File(directory, cacheKey)
        if (destination.isUsable()) return destination.touchForLru()

        val lock = keyLocks[cacheKey.hashCode() and (keyLocks.size - 1)]
        synchronized(lock) {
            if (destination.isUsable()) return destination.touchForLru()
            val temporary = File(directory, "$cacheKey.part-${android.os.Process.myPid()}-${Thread.currentThread().id}")
            runCatching { temporary.delete() }
            val client = requireNotNull(NavidromeRepository.client()) { "Navidrome is not configured" }
            try {
                Http.client.newCall(
                    Request.Builder().url(client.coverArtUrl(coverId, requestedSize)).build(),
                ).execute().use { response ->
                    check(response.isSuccessful) { "Cover request failed: HTTP ${response.code}" }
                    requireNotNull(response.body).byteStream().use { input ->
                        temporary.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                }
                check(temporary.length() > 0L) { "Navidrome returned empty artwork" }
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                heldBytes.addAndGet(destination.length())
                trim(directory, keep = destination)
                return destination.touchForLru()
            } finally {
                temporary.delete()
            }
        }
    }

    fun clear(context: Context) {
        synchronized(this) {
            directory(context).listFiles()?.forEach(File::delete)
            heldBytes.set(0L)
        }
    }

    private fun directory(context: Context): File {
        root?.let { return it }
        return synchronized(this) {
            root ?: File(context.noBackupFilesDir, "navidrome_artwork").apply {
                mkdirs()
                // An interrupted process can leave only its atomic staging file.
                // It is never a valid cache hit and must not consume the durable
                // artwork budget forever.
                listFiles()?.filter { it.name.contains(".part-") }?.forEach(File::delete)
                heldBytes.set(listFiles()?.sumOf(File::length) ?: 0L)
                root = this
            }
        }
    }

    private fun cacheKey(coverId: String, requestedSize: Int?): String {
        val server = NavidromeStore.config.value.serverUrl.trim().trimEnd('/')
        val size = requestedSize?.let(NavidromeIds::sizeBucket)?.toString() ?: "original"
        val raw = "$server\u0000$coverId\u0000$size"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun trim(directory: File, keep: File?) {
        if (heldBytes.get() <= maxBytes.get()) return
        directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it != keep && !it.name.contains(".part-") }
            .sortedBy(File::lastModified)
            .forEach { file ->
                if (heldBytes.get() <= maxBytes.get()) return
                val bytes = file.length()
                if (file.delete()) heldBytes.addAndGet(-bytes)
            }
    }

    private fun File.isUsable(): Boolean = isFile && length() > 0L

    private fun File.touchForLru(): File = apply {
        val now = System.currentTimeMillis()
        // Avoid a metadata write for every image bind/notification refresh.
        // Six-hour granularity is sufficient for the 1 GiB LRU ordering and
        // keeps cache hits genuinely read-only during ordinary scrolling.
        if (now - lastModified() >= LRU_TOUCH_INTERVAL_MS) setLastModified(now)
    }
}
