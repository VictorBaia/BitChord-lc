package com.music.bitchord

import android.app.Application
import android.os.Process
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.canvas.CanvasCache
import com.music.bitchord.data.cache.ImageCacheBudget
import com.music.bitchord.data.smb.SmbCoverFetcher
import com.music.bitchord.data.webdav.WebDavCoilAuth
import com.music.bitchord.data.canvas.SpotifyToken
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.LastPlayed
import com.music.bitchord.playback.OriginalVersion
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.navidrome.NavidromeStore
import com.music.bitchord.data.navidrome.NavidromeCoverFetcher
import com.music.bitchord.data.navidrome.NavidromeArtworkCache
import com.music.bitchord.data.scrobbling.LastFM
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.SearchHistory
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.stats.ArtistFacts
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.diagnostics.CrashReporter
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

class BitChordApplication : Application(), SingletonImageLoader.Factory {

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val startupStartedAt = SystemClock.elapsedRealtime()
        CrashReporter.init(this)
        // PlaybackService shares this process, so seeding the cookie here means
        // stream resolution is authenticated from the first play onwards.
        authStore = AuthStore(this)
        // Read the source mode before optional YouTube initialization is
        // scheduled. NavidromeStore only opens its encrypted preferences and
        // publishes an in-memory config; it does not perform network I/O.
        NavidromeStore.init(this)
        // Opened off the main thread, alongside everything below: none of these
        // reads a setting or the session, and between them they are the slowest
        // opens at startup — SourceRegistry's encrypted store most of all.
        // Started only once [AuthStore] exists, because both encrypted stores
        // share one keystore master key and a first launch must not have two
        // threads racing to create it. Optional work is allowed to finish
        // after the first Activity frame.
        val navidromeConfigured = NavidromeStore.config.value.isConfigured
        if (!navidromeConfigured) {
            thread(name = "source-init") {
                val startedAt = SystemClock.elapsedRealtime()
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runCatching {
                    SourceRegistry.init(this)
                    InnerTubeXResolver.init(this)
                }.onFailure { CrashReporter.recordNonFatal("startup_sources", it) }
                CrashReporter.note("startup_source_init_ms=${SystemClock.elapsedRealtime() - startedAt}")
            }
        }
        // Migration-safe: an old single cookie becomes the first encrypted
        // session, while newer installs restore the profile the listener chose.
        val restoredSession = authStore.activeSession
        if (restoredSession != null && authStore.activeAccountId == null) {
            authStore.select(restoredSession.accountId, restoredSession.activeProfileId)
        }
        authStore.cookie = restoredSession?.cookie
        Innertube.cookie = restoredSession?.cookie
        // Which account that cookie actually acts as. Read here rather than on
        // demand so the answer is usually in hand before the first request needs
        // it: a play registered under the wrong account is indistinguishable, to
        // the listener, from one that was never registered at all. Fire and
        // forget — every caller works without it, just less precisely.
        if (restoredSession != null && !navidromeConfigured) {
            // After the cookie, never before: setting the cookie clears any
            // channel the last session was acting as, so restoring the choice
            // first would restore it into the value about to be wiped.
            restoredSession.profiles.firstOrNull { it.profileId == restoredSession.activeProfileId }
                ?.let { Innertube.selectChannel(it.pageId, it.dataSyncId, it.authUser) }
            CoroutineScope(Dispatchers.IO).launch { Innertube.ensureSessionScope() }
        }
        AppSettings.init(this, authStore)
        val versionUpdated = AppSettings.consumeVersionUpdate(BuildConfig.VERSION_CODE)
        // Opening Media3 caches scans their indices and directories. With a
        // substantial playback/artwork library that can take hundreds of
        // milliseconds, so none of it belongs before the Activity's first
        // frame. DataSource creation waits for audio initialization on its own
        // loader thread if playback is restored unusually quickly.
        thread(name = "media-cache-init") {
            val startedAt = SystemClock.elapsedRealtime()
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runCatching {
                AudioCache.init(this)
                if (versionUpdated) AudioCache.clear()
            }.onFailure { CrashReporter.recordNonFatal("startup_audio_cache", it) }
            runCatching { CanvasCache.init(this) }
                .onFailure { CrashReporter.recordNonFatal("startup_canvas_cache", it) }
            // Artwork can already read/fill its cache through its lazy
            // directory path. Index/trim it only after playback-critical
            // caches are ready, avoiding an extra directory walk between
            // queue restoration and the first audible frame.
            runCatching { NavidromeArtworkCache.init(this) }
                .onFailure { CrashReporter.recordNonFatal("startup_artwork_cache", it) }
            CrashReporter.note("startup_media_cache_init_ms=${SystemClock.elapsedRealtime() - startedAt}")
        }
        // Restores a party this device is still a member of, so a process death
        // mid-session is something the rest of the party never sees. The socket
        // and the clock offset are not restored — both are re-established on
        // the next connect, which is the only way to be sure they are current.
        ListenTogether.init(this)
        SearchHistory.init(this)
        LastPlayed.init(this)
        com.music.bitchord.playback.PartyPersonalQueueStash.init(this)
        // Which tracks the listener has reverted to YouTube's own upload. Read
        // by [Song.toMediaItem], so it has to be open before the restart
        // snapshot below is turned back into queue items.
        OriginalVersion.init(this)
        // What's already saved to Downloads, so the song menu can say so
        // without a media-store query per row.
        Downloads.init(this)
        // The device's own listening record. Opened here rather than in
        // PlaybackService because the Replay page reads it from the UI side and
        // both live in this process — one owner, one directory.
        ListeningStats.init(this)
        // After AppSettings, whose switch decides whether half of it runs.
        ArtistFacts.init(this)
        // The offscreen WebView that mints a Spotify access token from the
        // listener's own session cookie needs a Context, and nothing in the
        // suspend call chain that reaches it (a track's canvas lookup) has
        // one to hand — see SpotifyToken's doc for why.
        if (!navidromeConfigured) {
            SpotifyToken.init(this)
        }
        // Audio's indexed byte ranges are implementation-specific and are
        // invalidated on update. Artwork is content-addressed by server, cover
        // id and size, so retaining it is safe and prevents an APK update from
        // downloading the whole visible library again.
        // Initialize LastFM with saved settings if available
        initLastfm()
        CrashReporter.note("startup_application_on_create_ms=${SystemClock.elapsedRealtime() - startupStartedAt}")
        // Do not hold Application.onCreate open for optional source/cache work.
        // The first Activity frame must not wait for encrypted source storage,
        // InnerTube setup, or Canvas cache creation.
    }

    /**
     * Artwork loading, which was previously left entirely on Coil's defaults.
     *
     * The defaults aren't unreasonable, but the disk cache is sized at 2% of
     * free space — which on a full phone is the 10MB floor, a few screens of
     * covers, and covers are exactly the thing worth still having tomorrow.
     * Naming a directory alongside it keeps that cache somewhere identifiable
     * rather than in the process's temp dir.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            // Covers on the WebDAV server need the credential or every one
            // of them 401s — which reads as "this track has no artwork".
            // Coil's own transport never sees Http.client's interceptor, so
            // the header is attached per request instead. See WebDavCoilAuth.
            .components {
                add(WebDavCoilAuth())
                // Covers filed on the SMB share; anything else falls
                // through to Coil's own fetchers. See SmbCoverFetcher.
                add(SmbCoverFetcher.Factory())
                add(NavidromeCoverFetcher.StringFactory())
                add(NavidromeCoverFetcher.UriFactory())
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(ImageCacheBudget.COIL_BYTES)
                    .build()
            }
            // Covers arriving with a hard cut read as the list flickering as
            // it scrolls; a short fade reads as them developing.
            .crossfade(200)
            .build()

    private fun initLastfm() {
        val sessionKey = AppSettings.lastfmSessionKey.value
        if (sessionKey.isBlank()) return
        val endpoint = AppSettings.lastfmEndpoint.value.ifBlank { LastFM.DEFAULT_API_ENDPOINT }
        val apiKey = AppSettings.lastfmApiKey.value.trim()
        val secret = AppSettings.lastfmSecret.value.trim()
        if (apiKey.isBlank() || secret.isBlank()) return
        LastFM.configure(
            endpoint = endpoint,
            apiKey = apiKey,
            secret = secret,
            sessionKey = sessionKey,
        )
    }

    companion object {
        lateinit var authStore: AuthStore
            private set
    }
}
