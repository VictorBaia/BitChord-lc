package com.music.bitchord.data.navidrome

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.Http
import okhttp3.Request
import java.io.FileNotFoundException
import kotlin.concurrent.thread

/** Streams authenticated artwork to Android's media notification without exposing credentials. */
class NavidromeArtworkProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri): String = "image/*"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Artwork is read-only")
        val coverId = uri.lastPathSegment?.takeIf { it.isNotBlank() }
            ?: throw FileNotFoundException("Missing cover id")
        val pipe = ParcelFileDescriptor.createPipe()
        thread(name = "navidrome-artwork") {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                runCatching {
                    val client = requireNotNull(NavidromeRepository.client())
                    Http.client.newCall(Request.Builder().url(client.coverArtUrl(coverId)).build())
                        .execute().use { response ->
                            if (!response.isSuccessful) throw FileNotFoundException("HTTP ${response.code}")
                            requireNotNull(response.body).byteStream().use { it.copyTo(output) }
                        }
                }
            }
        }
        return pipe[0]
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private val authority get() = "${BuildConfig.APPLICATION_ID}.navidrome-artwork"
        val URI_PREFIX get() = "content://$authority/"
        fun uri(coverId: String): Uri = Uri.parse(URI_PREFIX).buildUpon().appendPath(coverId).build()
    }
}
