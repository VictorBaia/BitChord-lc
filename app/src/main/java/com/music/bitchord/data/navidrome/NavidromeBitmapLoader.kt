package com.music.bitchord.data.navidrome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.music.bitchord.data.Http
import okhttp3.Request
import java.util.concurrent.Executors

/** Loads notification artwork inside the app process, where Navidrome credentials are available. */
@UnstableApi
class NavidromeBitmapLoader(context: Context) : BitmapLoader {
    private val fallback = DataSourceBitmapLoader.Builder(context)
        .setMaximumOutputDimension(1200)
        .setMakeShared(true)
        .build()
    private val executor = MoreExecutors.listeningDecorator(
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "navidrome-bitmap") },
    )

    override fun supportsMimeType(mimeType: String): Boolean = fallback.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = fallback.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val coverId = NavidromeIds.coverId(uri.toString()) ?: return fallback.loadBitmap(uri)
        return executor.submit<Bitmap> {
            val client = requireNotNull(NavidromeRepository.client()) { "Navidrome is not configured" }
            // Omitting `size` asks Navidrome for the stored artwork itself. The
            // system surface performs its own final presentation scaling.
            val bytes = Http.client.newCall(Request.Builder().url(client.coverArtUrl(coverId, size = null)).build())
                .execute().use { response ->
                    check(response.isSuccessful) { "Cover request failed: HTTP ${response.code}" }
                    requireNotNull(response.body).bytes()
                }
            requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
                "Navidrome returned invalid artwork"
            }
        }
    }
}
