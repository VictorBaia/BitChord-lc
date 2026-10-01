package com.music.bitchord.data.navidrome

import android.content.Context
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Reuses BitChord's native YouTube Music album page only for editorial metadata. */
internal object NavidromeAlbumEnrichment {
    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("navidrome_album_enrichment", Context.MODE_PRIVATE)
    }

    suspend fun description(
        cacheId: String,
        title: String,
        albumArtist: String,
        tracks: Set<String>,
    ): String? {
        if (!::prefs.isInitialized || title.isBlank() || albumArtist.isBlank()) return null
        val key = "album:$cacheId"
        prefs.getString(key, null)?.let { stored ->
            val json = runCatching { JSONObject(stored) }.getOrNull()
            if (json != null && System.currentTimeMillis() - json.optLong("at") < RETRY_AFTER_MS) {
                return json.text("description")
            }
        }

        val wantedTitle = normalized(title)
        val wantedArtist = normalized(albumArtist)
        val candidates = YtMusicRepository.search("$title $albumArtist", SearchFilter.ALBUMS).getOrNull()
            ?.filterIsInstance<SearchResult.Browse>()
            ?.map { it.item }
            ?.filter { item ->
                item.type == BrowseType.ALBUM &&
                    normalized(item.title) == wantedTitle &&
                    normalized(item.subtitle).contains(wantedArtist)
            }
            .orEmpty()

        val wantedTracks = tracks.map(::normalized).filter(String::isNotBlank).toSet()
        val description = candidates.firstNotNullOfOrNull { candidate ->
            val page = YtMusicRepository.browseSongs(candidate.browseId).getOrNull()
                ?: return@firstNotNullOfOrNull null
            val candidateTracks = page.songs.map { normalized(it.title) }.toSet()
            val overlap = wantedTracks.count(candidateTracks::contains)
            val required = minOf(2, wantedTracks.size).coerceAtLeast(1)
            page.description?.takeIf { overlap >= required }
        }

        prefs.edit().putString(
            key,
            JSONObject()
                .put("at", System.currentTimeMillis())
                .put("description", description ?: JSONObject.NULL)
                .toString(),
        ).apply()
        return description
    }

    private fun JSONObject.text(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun normalized(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private val RETRY_AFTER_MS = TimeUnit.DAYS.toMillis(14)
}
