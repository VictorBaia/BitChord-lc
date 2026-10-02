package com.music.bitchord.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Corrects Navidrome's estimated Content-Length on uncached transcodes.
 * Older Navidrome versions estimate kbps using 1024 instead of 1000, so the
 * declared size is about 2.4% larger than the response body. Media3 then keeps
 * the timeline alive past the last audio frame. Cached responses advertise byte
 * ranges and contain an actual length, so they are deliberately left untouched.
 */
class NavidromeTranscodeDataSource(private val upstream: DataSource) : DataSource {
    private var remaining = C.LENGTH_UNSET.toLong()
    private var correctedLength: Long? = null
    private var correctEstimatedLength = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        remaining = C.LENGTH_UNSET.toLong()
        correctedLength = null
        val markerName = "bitchordCorrectEstimatedLength"
        correctEstimatedLength = dataSpec.uri.getQueryParameter(markerName).equals("true", ignoreCase = true)
        val requestUri = dataSpec.uri.buildUpon().clearQuery().apply {
            dataSpec.uri.queryParameterNames
                .filterNot { it.equals(markerName, ignoreCase = true) }
                .forEach { name ->
                    dataSpec.uri.getQueryParameters(name).forEach { value -> appendQueryParameter(name, value) }
                }
        }.build()
        val request = if (requestUri == dataSpec.uri) dataSpec else dataSpec.buildUpon().setUri(requestUri).build()
        val reportedLength = upstream.open(request)
        val uri = upstream.uri ?: dataSpec.uri
        if (!correctEstimatedLength || !isEstimatedUncachedTranscode(uri)) return reportedLength

        val headerLength = upstream.responseHeaders.entries
            .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
            ?.value?.firstOrNull()?.toLongOrNull()
            ?: return reportedLength
        val sourceLength = headerLength
        val fixedLength = sourceLength * 1000L / 1024L
        if (fixedLength <= 0L || fixedLength >= sourceLength) return reportedLength
        correctedLength = fixedLength
        remaining = fixedLength
        return fixedLength
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val boundedLength = if (remaining > 0L) minOf(length.toLong(), remaining).toInt() else length
        val count = upstream.read(buffer, offset, boundedLength)
        if (count > 0 && remaining > 0L) remaining -= count
        return count
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> {
        val length = correctedLength ?: return upstream.responseHeaders
        val headers = upstream.responseHeaders.toMutableMap()
        val key = headers.keys.firstOrNull { it.equals("Content-Length", ignoreCase = true) }
        if (key != null) headers[key] = listOf(length.toString())
        return headers
    }

    override fun close() {
        remaining = C.LENGTH_UNSET.toLong()
        correctedLength = null
        correctEstimatedLength = false
        upstream.close()
    }

    private fun isEstimatedUncachedTranscode(uri: Uri): Boolean =
        uri.getQueryParameter("format") != null &&
            uri.getQueryParameter("estimateContentLength").equals("true", ignoreCase = true) &&
            !upstream.responseHeaders.entries.any { entry ->
                entry.key.equals("Accept-Ranges", ignoreCase = true) &&
                    entry.value.any { it.equals("bytes", ignoreCase = true) }
            }
}

class NavidromeTranscodeDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        NavidromeTranscodeDataSource(upstreamFactory.createDataSource())
}
