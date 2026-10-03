package com.music.bitchord.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** Streams only the bytes consumed by Media3; seeking starts a new Navidrome response. */
class NavidromeTranscodeDataSource(private val upstream: DataSource) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        if (uri.path?.endsWith("/stream.view") == true &&
            !uri.getQueryParameter("format").isNullOrBlank() &&
            NavidromeStreaming.isSeek(dataSpec.position)
        ) {
            val seconds = NavidromeStreaming.seekSeconds(dataSpec.position)
            Log.d("TRANSDBG", "timeOffset seconds=$seconds")
            val seekUri = uri.buildUpon()
                .appendQueryParameter("timeOffset", seconds.toString())
                .build()
            upstream.open(dataSpec.buildUpon()
                .setUri(seekUri)
                .setPosition(0L)
                .setLength(C.LENGTH_UNSET.toLong())
                .build())
            return C.LENGTH_UNSET.toLong()
        }
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()
}

class NavidromeTranscodeDataSourceFactory(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val upstreamFactory: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        NavidromeTranscodeDataSource(upstreamFactory.createDataSource())
}
