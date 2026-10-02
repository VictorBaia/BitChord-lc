package com.music.bitchord.data.navidrome

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/** Loads notification artwork inside the app process, where Navidrome credentials are available. */
@UnstableApi
class NavidromeBitmapLoader(private val context: Context) : BitmapLoader {
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
            val request = ImageRequest.Builder(context)
                .data(NavidromeIds.sizedCover(coverId, 1200))
                .size(1200)
                .allowHardware(false)
                .build()
            val result = runBlocking { SingletonImageLoader.get(context).execute(request) }
            requireNotNull((result as? SuccessResult)?.image?.toBitmap()) {
                "Navidrome returned invalid artwork"
            }
        }
    }
}
