package com.music.bitchord.data.navidrome

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.FileSystem
import okio.buffer
import okio.source

/** Auth is generated while Coil fetches the picture, never persisted in UI data. */
class NavidromeCoverFetcher private constructor(private val data: String) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val (id, requestedSize) = requireNotNull(NavidromeIds.coverIdAndSize(data))
        val client = requireNotNull(NavidromeRepository.client()) { "Navidrome is not configured" }
        val bytes = withContext(Dispatchers.IO) {
            Http.client.newCall(Request.Builder().url(client.coverArtUrl(id, requestedSize)).build()).execute().use { response ->
                check(response.isSuccessful) { "Cover request failed: HTTP ${response.code}" }
                requireNotNull(response.body).bytes()
            }
        }
        return SourceFetchResult(ImageSource(bytes.inputStream().source().buffer(), FileSystem.SYSTEM), null, DataSource.NETWORK)
    }
    class StringFactory : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (NavidromeIds.coverId(data) != null) NavidromeCoverFetcher(data) else null
    }

    /** Coil may map a String model to Uri before choosing a fetcher. */
    class UriFactory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            data.toString().takeIf { NavidromeIds.coverId(it) != null }?.let(::NavidromeCoverFetcher)
    }
}
