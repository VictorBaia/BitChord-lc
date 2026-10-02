package com.music.bitchord.data.navidrome

import android.net.Uri
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.ArtistPage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import org.json.JSONArray
import org.json.JSONObject

object NavidromeIds {
    const val LIKED = "nd:liked"
    private const val TRACK = "nd:"
    private const val ALBUM = "nd:album:"
    private const val ARTIST = "nd:artist:"
    private const val PLAYLIST = "nd:playlist:"
    private const val GENRE = "nd:genre:"

    fun track(id: String) = "$TRACK$id"
    fun album(id: String) = "$ALBUM$id"
    fun artist(id: String) = "$ARTIST$id"
    fun playlist(id: String) = "$PLAYLIST$id"
    fun genre(id: String) = "$GENRE$id"
    fun cover(id: String) = NavidromeArtworkProvider.uri(id).toString()
    /** Stable Coil model for one cover at one server-side size bucket. */
    fun sizedCover(id: String, size: Int): String =
        "bitchord://navidrome-cover/${Uri.encode(id)}?size=${sizeBucket(size)}"

    fun coverIdAndSize(value: String): Pair<String, Int?>? {
        val uri = runCatching { android.net.Uri.parse(value) }.getOrNull() ?: return null
        val id = when {
            value.startsWith("bitchord://navidrome-cover/") ->
                uri.pathSegments.lastOrNull()
            value.startsWith(NavidromeArtworkProvider.URI_PREFIX) ->
                uri.lastPathSegment
            else -> null
        }?.takeIf { it.isNotBlank() } ?: return null
        val size = uri.getQueryParameter("size")?.toIntOrNull()?.let(::sizeBucket)
        return id to size
    }

    fun sizeBucket(size: Int): Int = when {
        size <= 160 -> 160
        size <= 640 -> 640
        size <= 960 -> 960
        else -> 1200
    }

    fun coverId(url: String): String? = when {
        url.startsWith("bitchord://navidrome-cover/") -> url.removePrefix("bitchord://navidrome-cover/")
        url.startsWith(NavidromeArtworkProvider.URI_PREFIX) -> url.removePrefix(NavidromeArtworkProvider.URI_PREFIX)
        else -> null
    }?.substringBefore('?')?.let { android.net.Uri.decode(it) }?.takeIf { it.isNotBlank() }

    fun rawTrack(id: String): String? = id.removePrefix(TRACK).takeIf {
        id.startsWith(TRACK) && id != LIKED && !id.startsWith(ALBUM) && !id.startsWith(ARTIST) &&
            !id.startsWith(PLAYLIST) && !id.startsWith(GENRE)
    }
    fun rawAlbum(id: String): String? = id.removePrefix(ALBUM).takeIf { id.startsWith(ALBUM) }
    fun rawArtist(id: String): String? = id.removePrefix(ARTIST).takeIf { id.startsWith(ARTIST) }
    fun rawPlaylist(id: String): String? = id.removePrefix(PLAYLIST).takeIf { id.startsWith(PLAYLIST) }
    fun rawGenre(id: String): String? = id.removePrefix(GENRE).takeIf { id.startsWith(GENRE) }
    fun isNavidrome(id: String) = id.startsWith(TRACK) || id.startsWith(ALBUM) ||
        id == LIKED || id.startsWith(ARTIST) || id.startsWith(PLAYLIST) || id.startsWith(GENRE)
}

internal object NavidromeMapper {
    fun song(value: JSONObject): Song = Song(
        videoId = NavidromeIds.track(value.requiredId()),
        title = value.optString("title").ifBlank { value.optString("name") },
        artist = value.albumArtistName(),
        thumbnailUrl = value.optString("coverArt").takeIf { it.isNotBlank() }?.let(NavidromeIds::cover),
        durationText = value.optInt("duration").takeIf { it > 0 }?.asDuration(),
        artistId = value.albumArtistId()
            .takeIf { it.isNotBlank() }?.let(NavidromeIds::artist),
        albumId = value.optString("albumId").takeIf { it.isNotBlank() }?.let(NavidromeIds::album),
        albumName = value.optString("album").takeIf { it.isNotBlank() },
        isExplicit = value.optString("explicitStatus").trim().lowercase().let { status ->
            when (status) {
                "explicit" -> true
                "clean" -> false
                else -> null
            }
        },
        sourceQuality = value.optString("suffix").takeIf { it.isNotBlank() }?.uppercase(),
    )

    fun album(value: JSONObject): BrowseItem = BrowseItem(
        browseId = NavidromeIds.album(value.requiredId()),
        title = value.optString("name").ifBlank { value.optString("title") },
        subtitle = value.albumArtistName(),
        thumbnailUrl = value.optString("coverArt").takeIf { it.isNotBlank() }?.let(NavidromeIds::cover),
        type = BrowseType.ALBUM,
    )

    fun artist(value: JSONObject): BrowseItem = BrowseItem(
        browseId = NavidromeIds.artist(value.requiredId()),
        title = value.optString("name"),
        subtitle = "",
        thumbnailUrl = value.optString("coverArt").takeIf { it.isNotBlank() }?.let(NavidromeIds::cover),
        type = BrowseType.ARTIST,
    )

    fun playlist(value: JSONObject): BrowseItem = BrowseItem(
        browseId = NavidromeIds.playlist(value.requiredId()),
        title = value.optString("name"),
        subtitle = "",
        thumbnailUrl = value.optString("coverArt").takeIf { it.isNotBlank() }?.let(NavidromeIds::cover),
        type = BrowseType.PLAYLIST,
    )

    fun albumDetail(value: JSONObject): DetailPage = DetailPage(
        browseId = NavidromeIds.album(value.requiredId()),
        title = value.optString("name").ifBlank { value.optString("title") },
        subtitle = value.albumArtistName(),
        thumbnailUrl = value.optString("coverArt").takeIf { it.isNotBlank() }?.let(NavidromeIds::cover),
        songs = UiState.Success(values(value, "song").map(::song)),
        type = BrowseType.ALBUM,
    )

    fun artistDetail(value: JSONObject): ArtistPage = ArtistPage(
        songs = values(value, "song").map(::song),
        moreSongsBrowseId = null,
        sections = listOf(HomeShelf(value.optString("name"), values(value, "album").map(::album).map {
            com.music.bitchord.data.model.ShelfItem(it.title, it.subtitle, it.thumbnailUrl, null, it.browseId)
        })).filter { it.items.isNotEmpty() },
        thumbnailUrl = value.optString("coverArt").takeIf { it.isNotBlank() }?.let(NavidromeIds::cover),
        name = value.optString("name"),
    )

    fun search(value: JSONObject): List<SearchResult> = buildList {
        values(value, "song").forEachIndexed { index, item ->
            val result = if (index == 0) SearchResult.TopTrack(song(item)) else SearchResult.Track(song(item))
            add(result)
        }
        values(value, "album").forEach { add(SearchResult.Browse(album(it))) }
        values(value, "artist").forEach { add(SearchResult.Browse(artist(it))) }
    }

    fun values(parent: JSONObject, key: String): List<JSONObject> = when (val raw = parent.opt(key)) {
        is JSONArray -> List(raw.length()) { index -> raw.optJSONObject(index) }.filterNotNull()
        is JSONObject -> listOf(raw)
        else -> emptyList()
    }

    private fun JSONObject.requiredId(): String = optString("id").takeIf { it.isNotBlank() }
        ?: error("Navidrome response is missing id")

    private fun JSONObject.albumArtistName(): String = optString("albumArtist")
        .ifBlank { optJSONArray("albumArtists")?.optJSONObject(0)?.optString("name").orEmpty() }
        // Album objects expose their album artist in the standard `artist`
        // field; song objects normally take one of the branches above.
        .ifBlank { optString("artist") }

    private fun JSONObject.albumArtistId(): String = optString("albumArtistId")
        .ifBlank { optJSONArray("albumArtists")?.optJSONObject(0)?.optString("id").orEmpty() }
        .ifBlank { optString("artistId") }

    private fun Int.asDuration(): String = "%d:%02d".format(this / 60, this % 60)
}
