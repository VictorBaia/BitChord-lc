package com.music.bitchord.playback

import android.net.Uri
import com.music.bitchord.data.navidrome.NavidromeStore
import com.music.bitchord.data.navidrome.NavidromeStreamQuality
import com.music.bitchord.data.settings.AppSettings

/** Owned by a playback DataSource: queue construction never freezes its choice. */
internal class NavidromePlaybackQuality {
    private var selected: NavidromeStreamQuality? = null

    internal fun select(
        current: NavidromeStreamQuality,
        cached: NavidromeStreamQuality?,
    ): NavidromeStreamQuality = selected ?: (cached ?: current).also { selected = it }

    fun bind(uri: Uri, hasCachedBytes: (Uri) -> Boolean): Uri {
        if (uri.authority != "navidrome") return uri
        val config = NavidromeStore.config.value
        val old = NavidromeStreamQuality.entries.firstOrNull {
            (it.format ?: "original") == uri.getQueryParameter("qf") &&
                (it.maxKbps ?: 0) == (uri.getQueryParameter("qk")?.toIntOrNull() ?: 0)
        }
        val cached = old?.takeIf {
            selected == null && !config.forceStreaming && hasCachedBytes(uri)
        }
        val quality = select(
            config.streamQuality(AppSettings.meteredConnection.value == true),
            cached,
        )
        return uri.buildUpon().clearQuery().apply {
            uri.queryParameterNames.filterNot {
                it == "qf" || it == "qk" || it == "ndPlaybackSource"
            }.forEach { name ->
                uri.getQueryParameters(name).forEach { appendQueryParameter(name, it) }
            }
            appendQueryParameter("qf", quality.format ?: "original")
            quality.maxKbps?.let { appendQueryParameter("qk", it.toString()) }
        }.build()
    }

}
