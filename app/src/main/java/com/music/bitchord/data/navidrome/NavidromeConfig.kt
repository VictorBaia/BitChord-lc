package com.music.bitchord.data.navidrome

import java.net.URI
import java.util.Locale

/** The exact transcoding instruction sent to Navidrome's stream endpoint. */
enum class NavidromeStreamQuality(
    val format: String?,
    val maxKbps: Int?,
) {
    ORIGINAL(null, null),
    AAC_320("aac", 320),
    AAC_256("aac", 256),
    AAC_192("aac", 192),
    AAC_128("aac", 128),
    MP3_320("mp3", 320),
    MP3_256("mp3", 256),
    MP3_192("mp3", 192),
    MP3_128("mp3", 128),
    MP3_64("mp3", 64),
}

enum class NavidromeLyricsMode { APP_DEFAULT, NAVIDROME }

data class NavidromeConfig(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val displayName: String = "",
    val profileImageUri: String = "",
    val wifiStreamQuality: NavidromeStreamQuality = NavidromeStreamQuality.ORIGINAL,
    val cellularStreamQuality: NavidromeStreamQuality = NavidromeStreamQuality.AAC_192,
    val downloadStreamQuality: NavidromeStreamQuality = NavidromeStreamQuality.ORIGINAL,
    val lyricsMode: NavidromeLyricsMode = NavidromeLyricsMode.APP_DEFAULT,
    val enrichArtists: Boolean = true,
) {
    val effectiveDisplayName: String
        get() = displayName.trim().ifBlank { username }

    val isConfigured: Boolean
        get() = normalizedServerUrl() != null && username.isNotBlank() && password.isNotEmpty()

    fun normalizedServerUrl(): String? {
        val value = serverUrl.trim().trimEnd('/')
        if (!value.startsWith("https://", ignoreCase = true)) return null
        return runCatching {
            val uri = URI(value)
            uri.host?.takeIf { it.isNotBlank() }?.let { value }
        }.getOrNull()
    }

    fun endpoint(path: String): String? {
        val base = normalizedServerUrl() ?: return null
        // People commonly paste the documented API address ending in `/rest`.
        // Store and accept either that or the server root, but never build
        // `/rest/rest/...`.
        val serverRoot = base.removeSuffix("/rest").removeSuffix("/REST")
        return "$serverRoot/rest/${path.trimStart('/')}"
    }

    fun host(): String? = normalizedServerUrl()?.let { URI(it).host?.lowercase(Locale.ROOT) }

    fun streamQuality(metered: Boolean): NavidromeStreamQuality =
        if (metered) cellularStreamQuality else wifiStreamQuality
}
