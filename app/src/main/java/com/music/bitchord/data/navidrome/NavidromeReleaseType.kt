package com.music.bitchord.data.navidrome

import org.json.JSONArray
import org.json.JSONObject

/** The three release groups rendered by the existing artist-page shelves. */
internal enum class NavidromeReleaseType { ALBUM, EP, SINGLE }

internal fun JSONObject.navidromeReleaseType(): NavidromeReleaseType {
    val values = buildList {
        when (val raw = opt("releaseTypes")) {
            is JSONArray -> repeat(raw.length()) { index -> add(raw.optString(index)) }
            is String -> add(raw)
        }
        // Older Navidrome/OpenSubsonic responses used the singular spelling.
        optString("releaseType").takeIf(String::isNotBlank)?.let(::add)
    }.flatMap { it.split(',', ';', '/') }
        .map { it.trim().lowercase() }

    return when {
        values.any { it == "ep" || it == "extended play" } -> NavidromeReleaseType.EP
        values.any { it == "single" } -> NavidromeReleaseType.SINGLE
        else -> NavidromeReleaseType.ALBUM
    }
}

internal fun JSONObject.navidromeReleaseLabel(): String? = when (navidromeReleaseType()) {
    NavidromeReleaseType.EP -> "EP"
    NavidromeReleaseType.SINGLE -> "Single"
    NavidromeReleaseType.ALBUM -> null
}
