package com.music.bitchord.data.navidrome

import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.HomeFeed
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UserPlaylist
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.ArtistPage
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.LikeState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

object NavidromeRepository {
    fun client(): NavidromeClient? = NavidromeStore.config.value
        .takeIf { it.isConfigured }
        ?.let(::NavidromeClient)

    suspend fun health(): SourceHealth = client()?.health()
        ?: SourceHealth.Rejected("Configure o servidor, usuário e senha do Navidrome")

    suspend fun albums(type: String, size: Int = 30): Result<List<BrowseItem>> = call {
        val body = get("getAlbumList2.view", mapOf("type" to type, "size" to size.toString())).body
        NavidromeMapper.values(body.optJSONObject("albumList2") ?: return@call emptyList(), "album")
            .map(NavidromeMapper::album)
    }

    suspend fun search(query: String, filter: SearchFilter): Result<List<SearchResult>> = call {
        val counts = when (filter) {
            SearchFilter.SONGS -> mapOf("songCount" to "50", "albumCount" to "0", "artistCount" to "0")
            SearchFilter.ALBUMS -> mapOf("songCount" to "0", "albumCount" to "50", "artistCount" to "0")
            SearchFilter.ARTISTS -> mapOf("songCount" to "0", "albumCount" to "0", "artistCount" to "0")
            else -> mapOf("songCount" to "25", "albumCount" to "15", "artistCount" to "0")
        }
        val body = get("search3.view", counts + ("query" to query)).body
        val catalogue = NavidromeMapper.search(body.optJSONObject("searchResult3") ?: org.json.JSONObject())
        val albumArtists = if (filter == SearchFilter.ARTISTS || filter == SearchFilter.ALL) {
            NavidromeMapper.values(get("getArtists.view").body.optJSONObject("artists") ?: org.json.JSONObject(), "index")
                .flatMap { NavidromeMapper.values(it, "artist") }
                .filter { it.optString("name").contains(query, ignoreCase = true) }
                .sortedWith(compareBy<org.json.JSONObject> {
                    !it.optString("name").equals(query, ignoreCase = true)
                }.thenBy {
                    !it.optString("name").startsWith(query, ignoreCase = true)
                }.thenBy { it.optString("name").lowercase() })
                .map { SearchResult.Browse(NavidromeMapper.artist(it)) }
        } else emptyList()
        when (filter) {
            SearchFilter.ARTISTS -> albumArtists
            SearchFilter.ALL -> albumArtists + catalogue
            else -> catalogue
        }
    }

    suspend fun album(browseId: String): Result<DetailPage> = call {
        val id = requireNotNull(NavidromeIds.rawAlbum(browseId)) { "Not a Navidrome album" }
        val album = get("getAlbum.view", mapOf("id" to id)).body.getJSONObject("album")
        val info = runCatching {
            get("getAlbumInfo2.view", mapOf("id" to id)).body.optJSONObject("albumInfo")
        }.getOrNull()
        val page = NavidromeMapper.albumDetail(album)
        val externalDescription = if (info?.optString("notes").isNullOrBlank()) {
            NavidromeAlbumEnrichment.description(
                cacheId = "${NavidromeStore.config.value.host()}:$id",
                title = page.title,
                albumArtist = page.subtitle,
                tracks = (page.songs as? com.music.bitchord.data.model.UiState.Success)
                    ?.data.orEmpty().map(Song::title).toSet(),
            )
        } else null
        page.copy(
            subtitle = listOfNotNull(
                page.subtitle.takeIf(String::isNotBlank),
                albumReleaseYear(album)?.toString(),
            ).joinToString(" • "),
            description = info?.optString("notes")?.takeIf(String::isNotBlank) ?: externalDescription,
        )
    }

    suspend fun artist(
        browseId: String,
        albumsTitle: String,
        similarArtistsTitle: String,
    ): Result<ArtistPage> = call {
        val id = requireNotNull(NavidromeIds.rawArtist(browseId)) { "Not a Navidrome artist" }
        val artist = get("getArtist.view", mapOf("id" to id)).body.getJSONObject("artist")
        val albums = NavidromeMapper.values(artist, "album")
            .sortedByDescending(::albumReleaseKey)
        val artistName = artist.optString("name")
        val albumNames = albums.map { it.optString("name") }.toSet()
        val (info, albumPages, external) = coroutineScope {
            val infoRequest = async {
                runCatching {
                    get("getArtistInfo2.view", mapOf("id" to id, "count" to "20"))
                        .body.optJSONObject("artistInfo2")
                }.getOrNull()
            }
            val permits = Semaphore(6)
            val albumRequests = async {
                albums.map { album ->
                    async {
                        permits.withPermit {
                            runCatching {
                                get("getAlbum.view", mapOf("id" to album.getString("id")))
                                    .body.getJSONObject("album")
                            }.getOrNull()
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            val externalRequest = async {
                if (NavidromeStore.config.value.enrichArtists) {
                    NavidromeArtistEnrichment.find(
                        cacheId = "${NavidromeStore.config.value.host()}:$id",
                        name = artistName,
                        albums = albumNames,
                        tracks = emptySet(),
                    )
                } else null
            }
            Triple(infoRequest.await(), albumRequests.await(), externalRequest.await())
        }
        val discographySongs = albumPages
            .sortedByDescending { it.optString("created") }
            .flatMap { NavidromeMapper.values(it, "song").map(NavidromeMapper::song) }
            .distinctBy(Song::videoId)
        val albumShelf = HomeShelf(albumsTitle, albums.map { album ->
            val mapped = NavidromeMapper.album(album)
            com.music.bitchord.data.model.ShelfItem(
                mapped.title,
                albumReleaseYear(album)?.toString() ?: mapped.subtitle,
                mapped.thumbnailUrl,
                null,
                mapped.browseId,
            )
        })
        val similarShelf = HomeShelf(similarArtistsTitle, info?.let {
            NavidromeMapper.values(it, "similarArtist")
        }.orEmpty().mapNotNull { similar ->
            val mapped = runCatching { NavidromeMapper.artist(similar) }.getOrNull()
                ?: return@mapNotNull null
            com.music.bitchord.data.model.ShelfItem(
                mapped.title, mapped.subtitle, mapped.thumbnailUrl, null, mapped.browseId,
            )
        })
        val base = NavidromeMapper.artistDetail(artist).copy(
            songs = discographySongs,
            sections = listOf(albumShelf, similarShelf).filter { it.items.isNotEmpty() },
        )
        base.copy(
            thumbnailUrl = info?.optString("largeImageUrl")?.takeIf(String::isNotBlank)
                ?: info?.optString("mediumImageUrl")?.takeIf(String::isNotBlank)
                ?: base.thumbnailUrl ?: external?.image,
            description = info?.optString("biography")?.takeIf(String::isNotBlank) ?: external?.description,
            monthlyListenerCount = external?.monthlyListeners,
        )
    }

    suspend fun playlist(browseId: String): Result<DetailPage> = call {
        val id = requireNotNull(NavidromeIds.rawPlaylist(browseId)) { "Not a Navidrome playlist" }
        val value = get("getPlaylist.view", mapOf("id" to id)).body.getJSONObject("playlist")
        DetailPage(NavidromeIds.playlist(id), value.optString("name"), "",
            value.optString("coverArt").takeIf { it.isNotBlank() }?.let(NavidromeIds::cover),
            com.music.bitchord.data.model.UiState.Success(NavidromeMapper.values(value, "entry").map(NavidromeMapper::song)),
            com.music.bitchord.data.model.BrowseType.PLAYLIST)
    }

    suspend fun playlists(): Result<List<BrowseItem>> = call {
        NavidromeMapper.values(get("getPlaylists.view").body.optJSONObject("playlists") ?: return@call emptyList(), "playlist")
            .map(NavidromeMapper::playlist)
    }

    suspend fun userPlaylists(): Result<List<UserPlaylist>> = playlists().map { rows -> rows.map { row ->
        UserPlaylist("nd:${NavidromeIds.rawPlaylist(row.browseId)}", row.title, row.subtitle, row.thumbnailUrl)
    } }

    suspend fun rate(videoId: String, status: LikeStatus): Result<Unit> = call {
        val id = requireNotNull(NavidromeIds.rawTrack(videoId)) { "Not a Navidrome track" }
        get(if (status == LikeStatus.LIKE) "star.view" else "unstar.view", mapOf("id" to id)); Unit
    }

    suspend fun lyrics(videoId: String): Result<List<LyricLine>?> = call {
        val id = requireNotNull(NavidromeIds.rawTrack(videoId)) { "Not a Navidrome track" }
        NavidromeLyrics.parse(get("getLyricsBySongId.view", mapOf("id" to id, "enhanced" to "true")).body)
    }

    suspend fun songLyricsVersion(): Result<Int?> = call {
        val body = get("getOpenSubsonicExtensions.view").body
        val raw = body.opt("openSubsonicExtensions")
        val extensions = when (raw) {
            is org.json.JSONArray -> List(raw.length()) { raw.optJSONObject(it) }.filterNotNull()
            is org.json.JSONObject -> NavidromeMapper.values(raw, "openSubsonicExtension")
            else -> emptyList()
        }
        val extension = extensions.firstOrNull { it.optString("name").equals("songLyrics", true) }
        when (val versions = extension?.opt("versions")) {
            is org.json.JSONArray -> (0 until versions.length()).maxOfOrNull { versions.optInt(it) }
            is Number -> versions.toInt()
            else -> extension?.optInt("version")?.takeIf { it > 0 }
        }
    }

    suspend fun explore(genresTitle: String, moodsTitle: String): Result<List<MoodGenreSection>> = call {
        val genres = NavidromeMapper.values(
            get("getGenres.view").body.optJSONObject("genres") ?: org.json.JSONObject(), "genre",
        ).mapNotNull { genre ->
            genre.optString("value").takeIf { it.isNotBlank() }?.let { name ->
                MoodGenre(name, NavidromeIds.genre(name), null)
            }
        }
        val moods = NavidromeMapper.values(
            get("getPlaylists.view").body.optJSONObject("playlists") ?: org.json.JSONObject(), "playlist",
        ).map { playlist ->
            MoodGenre(
                playlist.optString("name"),
                NavidromeIds.playlist(playlist.getString("id")),
                null,
                playlist.optString("coverArt").takeIf(String::isNotBlank)?.let(NavidromeIds::cover),
            )
        }.filter { it.title.isNotBlank() }
        listOf(
            MoodGenreSection(genresTitle, genres),
            MoodGenreSection(moodsTitle, moods),
        ).filter { it.items.isNotEmpty() }
    }

    suspend fun moodGenreShelves(item: MoodGenre): Result<List<HomeShelf>> = call {
        when {
            NavidromeIds.rawGenre(item.browseId) != null -> {
                val genre = requireNotNull(NavidromeIds.rawGenre(item.browseId))
                val body = get("getAlbumList2.view", mapOf(
                    "type" to "byGenre", "genre" to genre, "size" to "100",
                )).body
                val albums = NavidromeMapper.values(body.optJSONObject("albumList2") ?: org.json.JSONObject(), "album")
                    .map(NavidromeMapper::album).map { it.toShelfItem() }
                listOf(HomeShelf(item.title, albums)).filter { it.items.isNotEmpty() }
            }
            NavidromeIds.rawPlaylist(item.browseId) != null -> {
                val page = playlist(item.browseId).getOrThrow()
                val songs = (page.songs as? com.music.bitchord.data.model.UiState.Success)?.data.orEmpty()
                listOf(HomeShelf(item.title, songs.map { song ->
                    com.music.bitchord.data.model.ShelfItem(
                        song.title, song.artist, song.thumbnailUrl, song.videoId, null,
                    )
                })).filter { it.items.isNotEmpty() }
            }
            else -> emptyList()
        }
    }

    suspend fun moodGenreArtwork(item: MoodGenre): Result<String?> = moodGenreShelves(item).map { shelves ->
        shelves.firstNotNullOfOrNull { shelf -> shelf.items.firstNotNullOfOrNull { it.thumbnailUrl } }
    }

    /** A short-lived stream URL; callers must never persist this value. */
    suspend fun stream(
        videoId: String,
        quality: NavidromeStreamQuality,
        timeOffsetSeconds: Long = 0,
    ): Result<SourceStream> = call {
        val id = requireNotNull(NavidromeIds.rawTrack(videoId)) { "Not a Navidrome track" }
        val song = get("getSong.view", mapOf("id" to id)).body.getJSONObject("song")
        val parameters = mutableMapOf("id" to id)
        quality.maxKbps?.let { parameters["maxBitRate"] = it.toString() }
        quality.format?.let { parameters["format"] = it }
        if (quality.format != null) parameters["estimateContentLength"] = "true"
        if (quality.format != null && timeOffsetSeconds > 0) {
            parameters["timeOffset"] = timeOffsetSeconds.toString()
        }
        val originalFormat = StreamFormat(
            codec = song.optString("suffix").lowercase().ifBlank { null },
            kbps = song.optInt("bitRate").takeIf { it > 0 },
            sampleRateHz = song.optInt("samplingRate").takeIf { it > 0 },
            bitDepth = song.optInt("bitDepth").takeIf { it > 0 },
        )
        SourceStream(
            url = authenticatedUrl("stream.view", parameters),
            format = quality.format?.let { StreamFormat(codec = it, kbps = quality.maxKbps) } ?: originalFormat,
        )
    }

    suspend fun scrobble(videoId: String, submission: Boolean, timeMillis: Long = System.currentTimeMillis()): Result<Unit> = call {
        val id = requireNotNull(NavidromeIds.rawTrack(videoId)) { "Not a Navidrome track" }
        get("scrobble.view", mapOf("id" to id, "submission" to submission.toString(), "time" to timeMillis.toString()))
        Unit
    }

    suspend fun autoplay(seed: Song, limit: Int): Result<List<Song>> = call {
        val id = requireNotNull(NavidromeIds.rawTrack(seed.videoId)) { "Not a Navidrome track" }
        val similar = get("getSimilarSongs2.view", mapOf("id" to id, "count" to limit.toString())).body
            .optJSONObject("similarSongs2")
        val songs = similar?.let { NavidromeMapper.values(it, "song").map(NavidromeMapper::song) }.orEmpty()
        if (songs.isNotEmpty()) songs else NavidromeMapper.values(
            get("getRandomSongs.view", mapOf("size" to limit.toString())).body.optJSONObject("randomSongs") ?: org.json.JSONObject(), "song",
        ).map(NavidromeMapper::song)
    }

    suspend fun createPlaylist(title: String, song: Song?): Result<String> = call {
        val parameters = mutableMapOf("name" to title)
        NavidromeIds.rawTrack(song?.videoId.orEmpty())?.let { parameters["songId"] = it }
        get("createPlaylist.view", parameters).body.getJSONObject("playlist").getString("id")
    }
    suspend fun addToPlaylist(playlistId: String, songId: String): Result<Unit> = update(
        playlistId, "songIdToAdd", requireNotNull(NavidromeIds.rawTrack(songId)) { "Not a Navidrome track" },
    )
    suspend fun removeFromPlaylist(playlistId: String, index: Int): Result<Unit> = update(playlistId, "songIndexToRemove", index.toString())
    suspend fun renamePlaylist(playlistId: String, title: String): Result<Unit> = update(playlistId, "name", title)
    suspend fun deletePlaylist(playlistId: String): Result<Unit> = call { get("deletePlaylist.view", mapOf("id" to rawPlaylistId(playlistId))); Unit }
    private suspend fun update(playlistId: String, key: String, value: String): Result<Unit> = call {
        get("updatePlaylist.view", mapOf("playlistId" to rawPlaylistId(playlistId), key to value)); Unit
    }
    private fun rawPlaylistId(playlistId: String) = playlistId.removePrefix("nd:")

    suspend fun home(
        newestTitle: String,
        recentTitle: String,
        frequentTitle: String,
        randomTitle: String,
    ): Result<HomeFeed> = call {
        val newest = albums("newest").getOrThrow()
        val recent = albums("recent").getOrThrow()
        val frequent = albums("frequent").getOrThrow()
        val random = albums("random").getOrThrow()
        HomeFeed(
            shelves = listOf(
                HomeShelf(newestTitle, newest.map { it.toShelfItem() }),
                HomeShelf(recentTitle, recent.map { it.toShelfItem() }),
                HomeShelf(frequentTitle, frequent.map { it.toShelfItem() }),
                HomeShelf(randomTitle, random.map { it.toShelfItem() }),
            ).filter { it.items.isNotEmpty() },
            continuation = null,
        )
    }

    suspend fun library(
        likedTitle: String,
        playlistsTitle: String,
        albumsTitle: String,
        artistsTitle: String,
    ): Result<LibraryPage> = call {
        val starredBody = get("getStarred2.view").body.optJSONObject("starred2") ?: org.json.JSONObject()
        val liked = NavidromeMapper.values(starredBody, "song").map(NavidromeMapper::song)
        // The player UI can derive this from LibraryPage, but the MediaSession
        // lives in the playback service and observes LikeState directly. Seed
        // that shared source so an already-starred Navidrome track reaches the
        // notification with a filled heart before the user touches it.
        LikeState.seedLiked(liked.mapTo(HashSet()) { it.videoId })
        val playlistItems = NavidromeMapper.values(get("getPlaylists.view").body.optJSONObject("playlists") ?: org.json.JSONObject(), "playlist").map(NavidromeMapper::playlist)
        val albums = buildList {
            var offset = 0
            while (true) {
                val page = NavidromeMapper.values(
                    get("getAlbumList2.view", mapOf(
                        "type" to "alphabeticalByName",
                        "size" to "500",
                        "offset" to offset.toString(),
                    )).body.optJSONObject("albumList2") ?: org.json.JSONObject(),
                    "album",
                )
                addAll(page)
                offset += page.size
                if (page.size < 500) break
            }
        }.distinctBy { it.optString("id") }.map(NavidromeMapper::album)
        val artists = NavidromeMapper.values(get("getArtists.view").body.optJSONObject("artists") ?: org.json.JSONObject(), "index")
            .flatMap { NavidromeMapper.values(it, "artist") }.map(NavidromeMapper::artist)
        LibraryPage(
            likedSongs = liked,
            librarySongs = emptyList(),
            shelves = listOf(
                HomeShelf(likedTitle, listOf(com.music.bitchord.data.model.ShelfItem(
                    title = likedTitle,
                    subtitle = liked.size.toString(),
                    thumbnailUrl = liked.firstOrNull()?.thumbnailUrl,
                    videoId = null,
                    browseId = NavidromeIds.LIKED,
                )).takeIf { liked.isNotEmpty() }.orEmpty()),
                HomeShelf(playlistsTitle, playlistItems.map { it.toShelfItem() }),
                HomeShelf(albumsTitle, albums.map { it.toShelfItem() }),
                HomeShelf(artistsTitle, artists.map { it.toShelfItem() }),
            ).filter { it.items.isNotEmpty() },
        )
    }

    suspend fun likedSongs(): Result<List<Song>> = call {
        val starred = get("getStarred2.view").body.optJSONObject("starred2") ?: org.json.JSONObject()
        NavidromeMapper.values(starred, "song").map(NavidromeMapper::song).also { songs ->
            LikeState.seedLiked(songs.mapTo(HashSet()) { it.videoId })
        }
    }

    private fun BrowseItem.toShelfItem() = com.music.bitchord.data.model.ShelfItem(
        title = title,
        subtitle = subtitle,
        thumbnailUrl = thumbnailUrl,
        videoId = null,
        browseId = browseId,
    )

    private fun albumReleaseYear(album: org.json.JSONObject): Int? =
        album.optJSONObject("originalReleaseDate")?.optInt("year")?.takeIf { it > 0 }
            ?: album.optJSONObject("releaseDate")?.optInt("year")?.takeIf { it > 0 }
            ?: album.optInt("year").takeIf { it > 0 }

    private fun albumReleaseKey(album: org.json.JSONObject): String {
        fun date(name: String): String? = album.optJSONObject(name)?.let { value ->
            val year = value.optInt("year").takeIf { it > 0 } ?: return@let null
            "%04d-%02d-%02d".format(year, value.optInt("month"), value.optInt("day"))
        }
        return date("originalReleaseDate") ?: date("releaseDate")
            ?: albumReleaseYear(album)?.let { "%04d-00-00".format(it) }
            ?: album.optString("created")
    }

    private suspend fun <T> call(block: suspend NavidromeClient.() -> T): Result<T> = withContext(Dispatchers.Default) {
        runCatching {
            requireNotNull(client()) { "Navidrome is not configured" }.block()
        }
    }
}
