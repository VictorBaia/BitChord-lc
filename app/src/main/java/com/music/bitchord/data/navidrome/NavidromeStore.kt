package com.music.bitchord.data.navidrome

import android.content.Context
import com.music.bitchord.auth.EncryptedPrefs
import kotlinx.coroutines.flow.MutableStateFlow

object NavidromeStore {
    private const val KEY_URL = "server_url"
    private const val KEY_USER = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_PROFILE_IMAGE_URI = "profile_image_uri"
    private const val KEY_WIFI_STREAM_QUALITY = "wifi_stream_quality"
    private const val KEY_CELLULAR_STREAM_QUALITY = "cellular_stream_quality"
    private const val KEY_DOWNLOAD_STREAM_QUALITY = "download_stream_quality"
    private const val KEY_LYRICS_MODE = "lyrics_mode"
    private const val KEY_ENRICH_ARTISTS = "enrich_artists"

    val config = MutableStateFlow(NavidromeConfig())

    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        prefs = EncryptedPrefs.open(context, "bitchord_navidrome", "bitchord_navidrome_plain")
        NavidromeArtistEnrichment.init(context)
        NavidromeAlbumEnrichment.init(context)
        publish(read())
    }

    fun update(next: NavidromeConfig) {
        check(::prefs.isInitialized) { "NavidromeStore.init must be called first" }
        val value = next.copy(serverUrl = next.serverUrl.trim().trimEnd('/'))
        prefs.edit()
            .putString(KEY_URL, value.serverUrl)
            .putString(KEY_USER, value.username)
            .putString(KEY_PASSWORD, value.password)
            .putString(KEY_DISPLAY_NAME, value.displayName)
            .putString(KEY_PROFILE_IMAGE_URI, value.profileImageUri)
            .putString(KEY_WIFI_STREAM_QUALITY, value.wifiStreamQuality.name)
            .putString(KEY_CELLULAR_STREAM_QUALITY, value.cellularStreamQuality.name)
            .putString(KEY_DOWNLOAD_STREAM_QUALITY, value.downloadStreamQuality.name)
            .putString(KEY_LYRICS_MODE, value.lyricsMode.name)
            .putBoolean(KEY_ENRICH_ARTISTS, value.enrichArtists)
            .apply()
        publish(value)
    }

    fun clear() = update(NavidromeConfig())

    private fun read() = NavidromeConfig(
        serverUrl = prefs.getString(KEY_URL, "").orEmpty(),
        username = prefs.getString(KEY_USER, "").orEmpty(),
        password = prefs.getString(KEY_PASSWORD, "").orEmpty(),
        displayName = prefs.getString(KEY_DISPLAY_NAME, "").orEmpty(),
        profileImageUri = prefs.getString(KEY_PROFILE_IMAGE_URI, "").orEmpty(),
        wifiStreamQuality = readQuality(KEY_WIFI_STREAM_QUALITY, NavidromeStreamQuality.ORIGINAL),
        cellularStreamQuality = readQuality(KEY_CELLULAR_STREAM_QUALITY, NavidromeStreamQuality.AAC_192),
        downloadStreamQuality = readQuality(KEY_DOWNLOAD_STREAM_QUALITY, NavidromeStreamQuality.ORIGINAL),
        lyricsMode = prefs.getString(KEY_LYRICS_MODE, null)?.let { stored ->
            NavidromeLyricsMode.entries.firstOrNull { it.name == stored }
        } ?: NavidromeLyricsMode.APP_DEFAULT,
        enrichArtists = prefs.getBoolean(KEY_ENRICH_ARTISTS, true),
    )

    private fun readQuality(key: String, fallback: NavidromeStreamQuality): NavidromeStreamQuality =
        prefs.getString(key, null)?.let { value ->
            NavidromeStreamQuality.entries.firstOrNull { it.name == value }
        } ?: fallback

    private fun publish(value: NavidromeConfig) {
        config.value = value
        NavidromeAuth.update(value)
    }
}
