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
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object NavidromeRepository {
    private const val NO_ARTWORK = "\u0000"
    private data class ArtistCatalogueCache(
        val server: String?,
        val loadedAt: Long,
        val artists: List<org.json.JSONObject>,
    )
    private data class PlaylistCatalogueCache(
        val server: String?,
        val loadedAt: Long,
        val playlists: List<org.json.JSONObject>,
    )
    private data class LibraryPayload(
        val liked: List<Song>,
        val playlists: List<BrowseItem>,
        val albums: List<BrowseItem>,
        val artists: List<BrowseItem>,
    )
    private data class AlbumPayload(
        val info: org.json.JSONObject?,
        val sameArtist: List<BrowseItem>,
        val similarAlbums: List<BrowseItem>,
        val externalDescription: String?,
    )

    @Volatile private var artistCatalogueCache: ArtistCatalogueCache? = null
    @Volatile private var playlistCatalogueCache: PlaylistCatalogueCache? = null
    private val playlistCatalogueMutex = Mutex()
    private val moodGenreArtworkCache = android.util.LruCache<String, String>(256)

    private suspend fun NavidromeClient.playlistCatalogue(): List<org.json.JSONObject> {
        val server = NavidromeStore.config.value.host()
        val now = android.os.SystemClock.elapsedRealtime()
        playlistCatalogueCache
            ?.takeIf { it.server == server && now - it.loadedAt < 30_000L }
            ?.let { return it.playlists }
        return playlistCatalogueMutex.withLock {
            val lockedNow = android.os.SystemClock.elapsedRealtime()
            playlistCatalogueCache
                ?.takeIf { it.server == server && lockedNow - it.loadedAt < 30_000L }
                ?.let { return@withLock it.playlists }
            NavidromeMapper.values(
                get("getPlaylists.view").body.optJSONObject("playlists") ?: org.json.JSONObject(),
                "playlist",
            ).also { playlists ->
                playlistCatalogueCache = PlaylistCatalogueCache(server, lockedNow, playlists)
            }
        }
    }

    private fun invalidatePlaylistCatalogue() {
        playlistCatalogueCache = null
    }

    private suspend fun NavidromeClient.albumArtists(): List<org.json.JSONObject> {
        val server = NavidromeStore.config.value.host()
        val now = android.os.SystemClock.elapsedRealtime()
        artistCatalogueCache?.takeIf { it.server == server && now - it.loadedAt < 5 * 60_000L }
            ?.let { return it.artists }
        val artists = NavidromeMapper.values(
            get("getArtists.view").body.optJSONObject("artists") ?: org.json.JSONObject(), "index",
        ).flatMap { NavidromeMapper.values(it, "artist") }
        artistCatalogueCache = ArtistCatalogueCache(server, now, artists)
        return artists
    }
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
        val (body, artists) = coroutineScope {
            val searchRequest = async {
                if (filter == SearchFilter.ARTISTS) org.json.JSONObject()
                else get("search3.view", counts + ("query" to query)).body
            }
            val artistRequest = async {
                if (filter == SearchFilter.ARTISTS || filter == SearchFilter.ALL) albumArtists() else emptyList()
            }
            searchRequest.await() to artistRequest.await()
        }
        val catalogue = NavidromeMapper.search(body.optJSONObject("searchResult3") ?: org.json.JSONObject())
        val albumArtists = if (filter == SearchFilter.ARTISTS || filter == SearchFilter.ALL) {
            artists
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

    /** Small typeahead payload; the UI only presents the first few unique labels. */
    suspend fun suggestions(query: String): Result<List<String>> = call {
        val (body, artists) = coroutineScope {
            val searchRequest = async {
                get("search3.view", mapOf(
                    "query" to query,
                    "songCount" to "8",
                    "albumCount" to "8",
                    "artistCount" to "0",
                )).body
            }
            val artistRequest = async { albumArtists() }
            searchRequest.await() to artistRequest.await()
        }
        val matchingArtists = artists
            .filter { it.optString("name").contains(query, ignoreCase = true) }
            .sortedWith(compareBy<org.json.JSONObject> {
                !it.optString("name").equals(query, ignoreCase = true)
            }.thenBy {
                !it.optString("name").startsWith(query, ignoreCase = true)
            }.thenBy { it.optString("name").lowercase() })
            .map { it.optString("name") }
        val catalogue = NavidromeMapper.search(
            body.optJSONObject("searchResult3") ?: org.json.JSONObject(),
        ).map { row -> when (row) {
            is SearchResult.TopTrack -> row.song.title
            is SearchResult.Track -> row.song.title
            is SearchResult.Browse -> row.item.title
        } }
        (matchingArtists + catalogue).filter(String::isNotBlank).distinctBy { it.lowercase() }.take(8)
    }

    suspend fun album(
        browseId: String,
        moreFromArtistTitle: String,
        recommendedTitle: String,
    ): Result<DetailPage> = call {
        val id = requireNotNull(NavidromeIds.rawAlbum(browseId)) { "Not a Navidrome album" }
        val album = get("getAlbum.view", mapOf("id" to id)).body.getJSONObject("album")
        val page = NavidromeMapper.albumDetail(album)
        val artistId = album.optString("artistId").ifBlank {
            album.optJSONArray("artists")?.optJSONObject(0)?.optString("id").orEmpty()
        }
        val currentAlbumId = album.optString("id")
        val (info, sameArtist, similarAlbums, externalDescription) = coroutineScope {
            val infoRequest = async {
                runCatching {
                    get("getAlbumInfo2.view", mapOf("id" to id)).body.optJSONObject("albumInfo")
                }.getOrNull()
            }
            val sameArtistRequest = async {
                if (artistId.isBlank()) emptyList() else runCatching {
                    val artistPage = get("getArtist.view", mapOf("id" to artistId))
                        .body.optJSONObject("artist") ?: org.json.JSONObject()
                    NavidromeMapper.values(artistPage, "album").map(NavidromeMapper::album)
                }.getOrDefault(emptyList())
            }
            val similarRequest = async {
                if (artistId.isBlank()) emptyList() else withTimeoutOrNull(2_000L) { runCatching {
                    val artistInfo = get("getArtistInfo2.view", mapOf("id" to artistId, "count" to "8"))
                        .body.optJSONObject("artistInfo2")
                    coroutineScope {
                        NavidromeMapper.values(artistInfo ?: org.json.JSONObject(), "similarArtist")
                            .take(5)
                            .mapNotNull { similar -> similar.optString("id").takeIf(String::isNotBlank) }
                            .map { similarId -> async {
                                runCatching {
                                    val artistPage = get("getArtist.view", mapOf("id" to similarId))
                                        .body.optJSONObject("artist") ?: org.json.JSONObject()
                                    NavidromeMapper.values(artistPage, "album").map(NavidromeMapper::album)
                                }.getOrDefault(emptyList())
                            } }
                            .awaitAll()
                            .flatten()
                    }
                }.getOrDefault(emptyList()) } ?: emptyList()
            }
            val externalRequest = async {
                withTimeoutOrNull(2_000L) {
                    NavidromeAlbumEnrichment.description(
                        cacheId = "${NavidromeStore.config.value.host()}:$id",
                        title = page.title,
                        albumArtist = page.subtitle,
                        tracks = (page.songs as? com.music.bitchord.data.model.UiState.Success)
                            ?.data.orEmpty().map(Song::title).toSet(),
                    )
                }
            }
            val loadedInfo = infoRequest.await()
            AlbumPayload(
                loadedInfo,
                sameArtistRequest.await(),
                similarRequest.await(),
                if (loadedInfo?.optString("notes").isNullOrBlank()) externalRequest.await() else null,
            )
        }
        val recommendationSections = listOf(
            HomeShelf(moreFromArtistTitle.format(page.subtitle), sameArtist.filterNot { it.browseId == NavidromeIds.album(currentAlbumId) }.distinctBy { it.browseId }.take(10).map { it.toShelfItem() }),
            HomeShelf(recommendedTitle, similarAlbums.filterNot { it.browseId == NavidromeIds.album(currentAlbumId) }.distinctBy { it.browseId }.take(10).map { it.toShelfItem() }),
        ).filter { it.items.isNotEmpty() }
        page.copy(
            subtitle = listOfNotNull(
                page.subtitle.takeIf(String::isNotBlank),
                album.navidromeReleaseLabel(),
                albumReleaseYear(album)?.toString(),
            ).joinToString(" • "),
            description = info?.optString("notes")?.takeIf(String::isNotBlank) ?: externalDescription,
            sections = recommendationSections,
        )
    }

    suspend fun artist(
        browseId: String,
        albumsTitle: String,
        epsTitle: String,
        singlesTitle: String,
        similarArtistsTitle: String,
    ): Result<ArtistPage> = call {
        val id = requireNotNull(NavidromeIds.rawArtist(browseId)) { "Not a Navidrome artist" }
        val artist = get("getArtist.view", mapOf("id" to id)).body.getJSONObject("artist")
        val albums = NavidromeMapper.values(artist, "album")
            .sortedByDescending(::albumReleaseKey)
        val artistName = artist.optString("name")
        val albumNames = albums.map { it.optString("name") }.toSet()
        val (info, discographySongs, external) = coroutineScope {
            val infoRequest = async {
                runCatching {
                    get("getArtistInfo2.view", mapOf("id" to id, "count" to "20"))
                        .body.optJSONObject("artistInfo2")
                }.getOrNull()
            }
            val songsRequest = async {
                runCatching {
                    val result = get("search3.view", mapOf(
                        "query" to artistName,
                        "songCount" to "500",
                        "albumCount" to "0",
                        "artistCount" to "0",
                    )).body.optJSONObject("searchResult3") ?: org.json.JSONObject()
                    NavidromeMapper.values(result, "song")
                        .filter { song ->
                            song.optString("albumArtistId") == id ||
                                song.optJSONArray("albumArtists")?.let { array ->
                                    (0 until array.length()).any { array.optJSONObject(it)?.optString("id") == id }
                                } == true
                        }
                        .sortedByDescending { it.optString("created") }
                        .map(NavidromeMapper::song)
                        .distinctBy(Song::videoId)
                }.getOrDefault(emptyList())
            }
            val externalRequest = async {
                if (NavidromeStore.config.value.enrichArtists) {
                    withTimeoutOrNull(2_000L) {
                        NavidromeArtistEnrichment.find(
                            cacheId = "${NavidromeStore.config.value.host()}:$id",
                            name = artistName,
                            albums = albumNames,
                            tracks = emptySet(),
                        )
                    }
                } else null
            }
            Triple(infoRequest.await(), songsRequest.await(), externalRequest.await())
        }
        fun releaseShelf(title: String, releases: List<org.json.JSONObject>) = HomeShelf(
            title,
            releases.map { album ->
                val mapped = NavidromeMapper.album(album)
                com.music.bitchord.data.model.ShelfItem(
                    mapped.title,
                    albumReleaseYear(album)?.toString() ?: mapped.subtitle,
                    mapped.thumbnailUrl,
                    null,
                    mapped.browseId,
                )
            },
        )
        val releaseShelves = listOf(
            releaseShelf(albumsTitle, albums.filter { it.navidromeReleaseType() == NavidromeReleaseType.ALBUM }),
            releaseShelf(epsTitle, albums.filter { it.navidromeReleaseType() == NavidromeReleaseType.EP }),
            releaseShelf(singlesTitle, albums.filter { it.navidromeReleaseType() == NavidromeReleaseType.SINGLE }),
        )
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
            sections = (releaseShelves + similarShelf).filter { it.items.isNotEmpty() },
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
        playlistCatalogue().map(NavidromeMapper::playlist)
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
        openSubsonicExtensionVersion("songLyrics")
    }

    suspend fun playbackReportVersion(): Result<Int?> = call {
        openSubsonicExtensionVersion("playbackReport")
    }

    private suspend fun NavidromeClient.openSubsonicExtensionVersion(name: String): Int? {
        val body = get("getOpenSubsonicExtensions.view").body
        val raw = body.opt("openSubsonicExtensions")
        val extensions = when (raw) {
            is org.json.JSONArray -> List(raw.length()) { raw.optJSONObject(it) }.filterNotNull()
            is org.json.JSONObject -> NavidromeMapper.values(raw, "openSubsonicExtension")
            else -> emptyList()
        }
        val extension = extensions.firstOrNull { it.optString("name").equals(name, true) }
        return when (val versions = extension?.opt("versions")) {
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
        val moods = playlistCatalogue().map { playlist ->
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

    suspend fun moodGenreArtwork(item: MoodGenre): Result<String?> = call {
        val cacheKey = "${NavidromeStore.config.value.host()}|${item.browseId}|${item.params.orEmpty()}"
        moodGenreArtworkCache.get(cacheKey)?.let { cached ->
            return@call cached.takeUnless { it == NO_ARTWORK }
        }
        when {
            item.thumbnailUrl != null -> item.thumbnailUrl
            NavidromeIds.rawGenre(item.browseId) != null -> {
                val genre = requireNotNull(NavidromeIds.rawGenre(item.browseId))
                val body = get("getAlbumList2.view", mapOf(
                    "type" to "byGenre", "genre" to genre, "size" to "1",
                )).body
                NavidromeMapper.values(
                    body.optJSONObject("albumList2") ?: org.json.JSONObject(), "album",
                ).firstOrNull()?.let(NavidromeMapper::album)?.thumbnailUrl
            }
            NavidromeIds.rawPlaylist(item.browseId) != null -> {
                val id = requireNotNull(NavidromeIds.rawPlaylist(item.browseId))
                val value = get("getPlaylist.view", mapOf("id" to id)).body.getJSONObject("playlist")
                value.optString("coverArt").takeIf(String::isNotBlank)?.let(NavidromeIds::cover)
                    ?: NavidromeMapper.values(value, "entry")
                        .firstOrNull()?.let(NavidromeMapper::song)?.thumbnailUrl
            }
            else -> null
        }.also { artwork -> moodGenreArtworkCache.put(cacheKey, artwork ?: NO_ARTWORK) }
    }

    /** A short-lived stream URL; callers must never persist this value. */
    suspend fun track(videoId: String): Result<Song> = call {
        val id = requireNotNull(NavidromeIds.rawTrack(videoId)) { "Not a Navidrome track" }
        val value = get("getSong.view", mapOf("id" to id)).body.getJSONObject("song")
        val song = NavidromeMapper.song(value)
        if (!song.artistId.isNullOrBlank()) return@call song

        // OpenSubsonic exposes `albumArtists` on a song, but older Subsonic
        // responses often expose only the recording artist there. The album
        // object still carries its canonical artist id. Resolve that id rather
        // than falling back to the track-level `artistId`, which would undo the
        // app-wide Album Artist rule for compilations and featured tracks.
        val rawAlbumId = value.optString("albumId").takeIf(String::isNotBlank)
            ?: return@call song
        val albumArtistId = runCatching {
            get("getAlbum.view", mapOf("id" to rawAlbumId))
                .body
                .getJSONObject("album")
                .let { album ->
                    album.optString("albumArtistId")
                        .ifBlank {
                            album.optJSONArray("albumArtists")
                                ?.optJSONObject(0)
                                ?.optString("id")
                                .orEmpty()
                        }
                        // On an album response this legacy field is the album
                        // artist, unlike the same field on a song response.
                        .ifBlank { album.optString("artistId") }
                }
        }.getOrNull()?.takeIf(String::isNotBlank)
        if (albumArtistId == null) song
        else song.copy(artistId = NavidromeIds.artist(albumArtistId))
    }

    /** A short-lived stream URL; callers must never persist this value. */
    suspend fun stream(
        videoId: String,
        quality: NavidromeStreamQuality,
        timeOffsetSeconds: Long = 0,
    ): Result<SourceStream> = call {
        val id = requireNotNull(NavidromeIds.rawTrack(videoId)) { "Not a Navidrome track" }
        val songResponse = get("getSong.view", mapOf("id" to id))
        val song = songResponse.body.getJSONObject("song")
        val parameters = mutableMapOf("id" to id)
        quality.maxKbps?.let { parameters["maxBitRate"] = it.toString() }
        // Omitting format lets Navidrome apply the player's default transcode.
        // "raw" explicitly preserves the source when ORIGINAL is selected.
        parameters["format"] = quality.format ?: "raw"
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
            durationSec = song.optInt("duration").takeIf { it > 0 },
        )
    }

    suspend fun scrobble(videoId: String, submission: Boolean, timeMillis: Long = System.currentTimeMillis()): Result<Unit> = call {
        val id = requireNotNull(NavidromeIds.rawTrack(videoId)) { "Not a Navidrome track" }
        get("scrobble.view", mapOf("id" to id, "submission" to submission.toString(), "time" to timeMillis.toString()))
        Unit
    }

    suspend fun reportPlayback(
        videoId: String,
        positionMs: Long,
        state: String,
    ): Result<Unit> = call {
        require(state in setOf("starting", "playing", "paused", "stopped")) { "Invalid playback state" }
        val id = requireNotNull(NavidromeIds.rawTrack(videoId)) { "Not a Navidrome track" }
        get("reportPlayback.view", mapOf(
            "mediaId" to id,
            "mediaType" to "song",
            "positionMs" to positionMs.coerceAtLeast(0L).toString(),
            "state" to state,
            // State reporting only; existing scrobble submission remains the
            // sole source of play-count updates, avoiding duplicate scrobbles.
            "ignoreScrobble" to "true",
        ))
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
            .also { invalidatePlaylistCatalogue() }
    }
    suspend fun addToPlaylist(playlistId: String, songId: String): Result<Unit> = update(
        playlistId, "songIdToAdd", requireNotNull(NavidromeIds.rawTrack(songId)) { "Not a Navidrome track" },
    )
    suspend fun removeFromPlaylist(playlistId: String, index: Int): Result<Unit> = update(playlistId, "songIndexToRemove", index.toString())
    suspend fun renamePlaylist(playlistId: String, title: String): Result<Unit> = update(playlistId, "name", title)
    suspend fun deletePlaylist(playlistId: String): Result<Unit> = call {
        get("deletePlaylist.view", mapOf("id" to rawPlaylistId(playlistId)))
        invalidatePlaylistCatalogue()
    }

    /** Requests Navidrome's server-side full library scan. */
    suspend fun startFullScan(): Result<Unit> = call {
        val started = get("startScan.view", mapOf("fullScan" to "true"))
        check(started.status == "ok") { started.errorMessage.ifBlank { "Navidrome rejected the full scan" } }
        while (true) {
            delay(1_500L)
            val status = get("getScanStatus.view")
            check(status.status == "ok") { status.errorMessage.ifBlank { "Could not read Navidrome scan status" } }
            val scanning = status.body
                .optJSONObject("scanStatus")
                ?.optBoolean("scanning", false) == true
            if (!scanning) break
        }
        artistCatalogueCache = null
        invalidatePlaylistCatalogue()
    }
    private suspend fun update(playlistId: String, key: String, value: String): Result<Unit> = call {
        get("updatePlaylist.view", mapOf("playlistId" to rawPlaylistId(playlistId), key to value))
        invalidatePlaylistCatalogue()
    }
    private fun rawPlaylistId(playlistId: String) = playlistId.removePrefix("nd:")

    suspend fun home(
        newestTitle: String,
        recentTitle: String,
        frequentTitle: String,
        randomTitle: String,
    ): Result<HomeFeed> = call {
        val (newest, recent, frequent, random) = coroutineScope {
            val requests = listOf("newest", "recent", "frequent", "random").map { type ->
                async { albums(type).getOrThrow() }
            }.awaitAll()
            requests
        }
        HomeFeed(
            shelves = listOf(
                HomeShelf(recentTitle, recent.map { it.toShelfItem() }, isRecents = true),
                HomeShelf(newestTitle, newest.map { it.toShelfItem() }),
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
        val payload = coroutineScope {
            val likedRequest = async {
                val body = get("getStarred2.view").body.optJSONObject("starred2") ?: org.json.JSONObject()
                NavidromeMapper.values(body, "song").map(NavidromeMapper::song)
            }
            val playlistsRequest = async { playlistCatalogue().map(NavidromeMapper::playlist) }
            val albumsRequest = async {
                buildList {
                    var offset = 0
                    while (true) {
                        val page = NavidromeMapper.values(
                            get("getAlbumList2.view", mapOf(
                                "type" to "alphabeticalByName", "size" to "500", "offset" to offset.toString(),
                            )).body.optJSONObject("albumList2") ?: org.json.JSONObject(), "album",
                        )
                        addAll(page)
                        offset += page.size
                        if (page.size < 500) break
                    }
                }.distinctBy { it.optString("id") }.map(NavidromeMapper::album)
            }
            val artistsRequest = async { albumArtists().map(NavidromeMapper::artist) }
            LibraryPayload(
                likedRequest.await(), playlistsRequest.await(), albumsRequest.await(), artistsRequest.await(),
            )
        }
        val liked = payload.liked
        val playlistItems = payload.playlists
        val albums = payload.albums
        val artists = payload.artists
        // The player UI can derive this from LibraryPage, but the MediaSession
        // lives in the playback service and observes LikeState directly. Seed
        // that shared source so an already-starred Navidrome track reaches the
        // notification with a filled heart before the user touches it.
        LikeState.seedLiked(liked.mapTo(HashSet()) { it.videoId })
        LibraryPage(
            likedSongs = liked,
            librarySongs = emptyList(),
            shelves = listOf(
                HomeShelf(likedTitle, listOf(com.music.bitchord.data.model.ShelfItem(
                    title = likedTitle,
                    subtitle = liked.size.toString(),
                    thumbnailUrl = NavidromeIds.LIKED_ARTWORK,
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
