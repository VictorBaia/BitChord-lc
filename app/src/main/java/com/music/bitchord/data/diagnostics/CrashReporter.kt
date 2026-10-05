package com.music.bitchord.data.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.net.TrafficStats
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Persistent, user-exportable diagnostics for fatal Java crashes and Android process exits. */
object CrashReporter {
    private const val MAX_REPORTS = 5
    private const val MAX_TRACE_BYTES = 512 * 1024
    private const val MAX_BREADCRUMBS = 160
    private const val MAX_BREADCRUMB_CHARS = 320
    private const val MAX_RESOURCE_SAMPLES = 80
    private const val RESOURCE_SAMPLE_SECONDS = 15L
    private val writingFatal = AtomicBoolean(false)
    private val breadcrumbs = ArrayDeque<String>()
    private val resourceSamples = ArrayDeque<String>()
    private val monitorStarted = AtomicBoolean(false)
    @Volatile private var appContext: Context? = null
    @Volatile private var previousCpuMs = 0L
    @Volatile private var previousSampleMs = 0L
    @Volatile private var baselineRxBytes = TrafficStats.UNSUPPORTED.toLong()
    @Volatile private var baselineTxBytes = TrafficStats.UNSUPPORTED.toLong()
    @Volatile private var previousRxBytes = TrafficStats.UNSUPPORTED.toLong()
    @Volatile private var previousTxBytes = TrafficStats.UNSUPPORTED.toLong()

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (writingFatal.compareAndSet(false, true)) {
                runCatching { writeFatal(thread, error) }
            }
            previous?.uncaughtException(thread, error)
                ?: Process.killProcess(Process.myPid())
        }
        note("process_started build=${BuildConfig.VERSION_NAME}/${BuildConfig.BUILD_TYPE}")
        baselineRxBytes = TrafficStats.getUidRxBytes(Process.myUid())
        baselineTxBytes = TrafficStats.getUidTxBytes(Process.myUid())
        previousRxBytes = baselineRxBytes
        previousTxBytes = baselineTxBytes
        startResourceMonitor(context.applicationContext)
    }

    fun note(message: String) {
        val clean = redact(message).replace('\n', ' ').take(MAX_BREADCRUMB_CHARS)
        val line = "${format(CLOCK, Date())} [${Thread.currentThread().name}] $clean"
        synchronized(breadcrumbs) {
            breadcrumbs.addLast(line)
            while (breadcrumbs.size > MAX_BREADCRUMBS) breadcrumbs.removeFirst()
        }
    }

    fun recordNonFatal(source: String, error: Throwable) {
        note("non_fatal source=$source type=${error.javaClass.name} message=${error.message.orEmpty()}")
        TrackLog.e("CrashReporter", "Non-fatal coroutine failure in $source", error)
    }

    suspend fun exportTo(context: Context, target: android.net.Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val report = buildExport(context.applicationContext)
            requireNotNull(context.contentResolver.openOutputStream(target, "w")).bufferedWriter().use {
                it.write(report)
            }
        }
    }

    fun suggestedName(): String = "myBichord-crash-${format(FILE_CLOCK, Date())}.txt"

    private fun startResourceMonitor(context: Context) {
        if (!monitorStarted.compareAndSet(false, true)) return
        val executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "myBichord-diagnostics").apply { isDaemon = true }
        }
        executor.scheduleWithFixedDelay(
            { runCatching { sampleResources(context) } },
            RESOURCE_SAMPLE_SECONDS,
            RESOURCE_SAMPLE_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    private fun sampleResources(context: Context) {
        val now = SystemClock.elapsedRealtime()
        val cpu = Process.getElapsedCpuTime()
        val elapsed = now - previousSampleMs
        val cpuPercent = if (previousSampleMs > 0L && elapsed > 0L) {
            ((cpu - previousCpuMs) * 100f / elapsed).coerceAtLeast(0f)
        } else {
            0f
        }
        previousSampleMs = now
        previousCpuMs = cpu
        val rxBytes = TrafficStats.getUidRxBytes(Process.myUid())
        val txBytes = TrafficStats.getUidTxBytes(Process.myUid())
        val sessionRx = trafficDelta(rxBytes, baselineRxBytes)
        val sessionTx = trafficDelta(txBytes, baselineTxBytes)
        val intervalRx = trafficDelta(rxBytes, previousRxBytes)
        val intervalTx = trafficDelta(txBytes, previousTxBytes)
        previousRxBytes = rxBytes
        previousTxBytes = txBytes
        val runtime = Runtime.getRuntime()
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: -1
        } else {
            -1
        }
        val mainPostedAt = SystemClock.uptimeMillis()
        Handler(Looper.getMainLooper()).post {
            val delayMs = SystemClock.uptimeMillis() - mainPostedAt
            if (delayMs >= 750L) note("main_thread_stall delayMs=$delayMs")
        }
        val line = buildString {
            append(format(CLOCK, Date()))
            append(" heap=").append(runtime.totalMemory() - runtime.freeMemory())
            append('/').append(runtime.maxMemory())
            append(" native=").append(Debug.getNativeHeapAllocatedSize())
            append(" pssKb=").append(Debug.getPss())
            append(" cpuPct=").append(String.format(Locale.US, "%.1f", cpuPercent))
            append(" sessionRx=").append(sessionRx)
            append(" sessionTx=").append(sessionTx)
            append(" intervalRx=").append(intervalRx)
            append(" intervalTx=").append(intervalTx)
            append(" threads=").append(Thread.getAllStackTraces().size)
            append(" thermal=").append(thermal)
        }
        synchronized(resourceSamples) {
            resourceSamples.addLast(line)
            while (resourceSamples.size > MAX_RESOURCE_SAMPLES) resourceSamples.removeFirst()
        }
    }

    private fun writeFatal(thread: Thread, error: Throwable) {
        val context = appContext ?: return
        val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
        val file = File(dir, "fatal-${System.currentTimeMillis()}.txt")
        file.writeText(buildString {
            appendHeader("FATAL EXCEPTION")
            appendLine("thread=${thread.name} id=${thread.id} state=${thread.state}")
            appendLine(redact(error.stackTraceToString()))
            appendRuntime()
            appendResourceSamples()
            appendBreadcrumbs()
            appendLine("\n--- recent playback log ---")
            appendLine(redact(TrackLog.recentDump()))
            appendThreads()
        })
        dir.listFiles { candidate -> candidate.name.startsWith("fatal-") }
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_REPORTS)
            ?.forEach { it.delete() }
    }

    private fun buildExport(context: Context) = buildString {
        appendHeader("myBichord diagnostics export")
        appendRuntime()
        appendResourceSamples()
        appendBreadcrumbs()
        appendLine("\n--- recent playback log ---")
        appendLine(redact(TrackLog.recentDump()))
        val stored = File(context.filesDir, "diagnostics")
            .listFiles { file -> file.name.startsWith("fatal-") }
            .orEmpty().sortedByDescending(File::lastModified)
        appendLine("\n--- stored fatal reports (${stored.size}) ---")
        stored.forEach { file ->
            appendLine("\n### ${file.name}")
            appendLine(redact(runCatching { file.readText() }.getOrDefault("<unreadable>")))
        }
        appendExitHistory(context)
    }

    private fun StringBuilder.appendHeader(title: String) {
        appendLine(title)
        appendLine("generated=${format(DATE_CLOCK, Date())}")
        appendLine("build=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        appendLine("process=${Process.myPid()} uptimeMs=${android.os.SystemClock.elapsedRealtime()}")
    }

    private fun StringBuilder.appendRuntime() {
        val runtime = Runtime.getRuntime()
        appendLine("\n--- runtime ---")
        appendLine("javaHeapUsed=${runtime.totalMemory() - runtime.freeMemory()} javaHeapMax=${runtime.maxMemory()}")
        appendLine("nativeHeapAllocated=${Debug.getNativeHeapAllocatedSize()} nativeHeapSize=${Debug.getNativeHeapSize()}")
        appendLine("threads=${Thread.getAllStackTraces().size}")
    }

    private fun StringBuilder.appendBreadcrumbs() {
        appendLine("\n--- breadcrumbs ---")
        synchronized(breadcrumbs) { breadcrumbs.forEach(::appendLine) }
    }

    private fun StringBuilder.appendResourceSamples() {
        appendLine("\n--- rolling resource samples (15s) ---")
        synchronized(resourceSamples) { resourceSamples.forEach(::appendLine) }
    }

    private fun StringBuilder.appendThreads() {
        appendLine("\n--- all threads ---")
        Thread.getAllStackTraces().entries.sortedBy { it.key.name }.forEach { (thread, stack) ->
            appendLine("\n[${thread.name}] id=${thread.id} state=${thread.state}")
            stack.forEach { appendLine("  at $it") }
        }
    }

    private fun StringBuilder.appendExitHistory(context: Context) {
        appendLine("\n--- Android historical process exits ---")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            appendLine("Unavailable below Android 11")
            return
        }
        val manager = context.getSystemService(ActivityManager::class.java)
        val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 12)
        if (exits.isEmpty()) appendLine("none reported")
        exits.forEachIndexed { index, exit ->
            appendLine("\n### exit ${index + 1}")
            appendLine("timestamp=${format(DATE_CLOCK, Date(exit.timestamp))}")
            appendLine("reason=${exit.reasonName()} status=${exit.status} importance=${exit.importance}")
            appendLine("pss=${exit.pss} rss=${exit.rss} description=${redact(exit.description.orEmpty())}")
            val trace = runCatching { exit.traceInputStream?.readLimited(MAX_TRACE_BYTES) }.getOrNull()
            if (!trace.isNullOrBlank()) appendLine(redact(trace))
        }
    }

    private fun ApplicationExitInfo.reasonName(): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        else -> "OTHER_$reason"
    }

    private fun InputStream.readLimited(limit: Int): String = use { input ->
        val bytes = ByteArray(limit)
        var total = 0
        while (total < limit) {
            val read = input.read(bytes, total, limit - total)
            if (read <= 0) break
            total += read
        }
        String(bytes, 0, total, Charsets.UTF_8)
    }

    private fun redact(value: String): String = value
        .replace(Regex("(?i)(password|passwd|token|api[_-]?key|secret|authorization)=([^&\\s]+)"), "$1=<redacted>")
        .replace(Regex("(?i)(Authorization:\\s*)([^\\r\\n]+)"), "$1<redacted>")
        .replace(Regex("(?i)([?&](?:u|p|t|s|apiKey)=[^&\\s]+)"), "&<redacted>")

    private fun trafficDelta(current: Long, baseline: Long): Long =
        if (current == TrafficStats.UNSUPPORTED.toLong() || baseline == TrafficStats.UNSUPPORTED.toLong()) {
            TrafficStats.UNSUPPORTED.toLong()
        } else {
            (current - baseline).coerceAtLeast(0L)
        }

    private val CLOCK = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val FILE_CLOCK = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val DATE_CLOCK = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)

    private fun format(formatter: SimpleDateFormat, date: Date): String =
        synchronized(formatter) { formatter.format(date) }
}
