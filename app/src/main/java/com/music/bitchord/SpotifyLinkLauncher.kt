package com.music.bitchord

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens Spotify links in the installed client and keeps a browser fallback. */
internal object SpotifyLinkLauncher {
    private const val SPOTIFY_PACKAGE = "com.spotify.music"

    fun open(context: Context, target: String) {
        val appUri = spotifyAppUri(target)
        val appIntent = Intent(Intent.ACTION_VIEW, appUri).setPackage(SPOTIFY_PACKAGE)
        if (runCatching { context.startActivity(appIntent) }.isSuccess) return

        val fallback = webUri(target)
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, fallback)) }
    }

    private fun spotifyAppUri(target: String): Uri {
        val uri = Uri.parse(target.trim())
        // Spotify's Android content-linking contract expects its canonical
        // open.spotify.com URL. Converting that URL to the legacy spotify:
        // scheme can launch the client without routing to the entity.
        return uri
    }

    private fun webUri(target: String): Uri {
        val uri = Uri.parse(target.trim())
        if (!uri.scheme.equals("spotify", ignoreCase = true)) return uri
        val parts = target.split(':')
        return if (parts.size >= 3 && parts[1] == "track") {
            Uri.parse("https://open.spotify.com/track/${parts[2]}")
        } else {
            Uri.parse("https://open.spotify.com")
        }
    }
}
