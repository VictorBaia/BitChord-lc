package com.music.bitchord.data.navidrome

import com.music.bitchord.data.lyrics.LyricAlignment
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricWord
import org.json.JSONArray
import org.json.JSONObject

internal object NavidromeLyrics {
    fun parse(body: JSONObject): List<LyricLine>? {
        val entries = values(body.optJSONObject("lyricsList"), "structuredLyrics")
        val main = entries.firstOrNull { it.optString("kind", "main") == "main" }
            ?: entries.firstOrNull()
            ?: return null
        val offset = main.optLong("offset", 0L)
        val fallback = values(main, "line")
        val cueLines = values(main, "cueLine")
        if (main.optBoolean("synced") && cueLines.isNotEmpty()) {
            return enhanced(main, fallback, cueLines, offset).takeIf(List<LyricLine>::isNotEmpty)
        }
        return fallback.mapIndexedNotNull { index, line ->
            val text = line.optString("value")
            if (text.isBlank()) return@mapIndexedNotNull null
            val start = line.optLong("start", if (main.optBoolean("synced")) index.toLong() else 0L) + offset
            LyricLine(start.coerceAtLeast(0L), text)
        }.takeIf(List<LyricLine>::isNotEmpty)
    }

    private fun enhanced(
        entry: JSONObject,
        fallback: List<JSONObject>,
        cueLines: List<JSONObject>,
        offset: Long,
    ): List<LyricLine> {
        val roles = values(entry, "agents").associate { it.optString("id") to it.optString("role") }
        val voices = roles.keys.filter { roles[it] != "bg" }
        return cueLines.groupBy { it.optInt("index", -1) }.toSortedMap().mapNotNull { (index, layers) ->
            val lead = layers.firstOrNull { roles[it.optString("agentId")] == "main" }
                ?: layers.firstOrNull { roles[it.optString("agentId")] != "bg" }
                ?: layers.firstOrNull()
                ?: return@mapNotNull null
            val agentId = lead.optString("agentId")
            val text = lead.optString("value").ifBlank { fallback.getOrNull(index)?.optString("value").orEmpty() }
            if (text.isBlank()) return@mapNotNull null
            val background = layers.firstOrNull { roles[it.optString("agentId")] == "bg" }
                ?.toLine(offset, LyricAlignment.Start)
            val alignment = if (agentId.isNotBlank() && voices.indexOf(agentId) > 0) {
                LyricAlignment.End
            } else {
                LyricAlignment.Start
            }
            lead.toLine(offset, alignment, background)
        }
    }

    private fun JSONObject.toLine(
        offset: Long,
        alignment: LyricAlignment,
        background: LyricLine? = null,
    ): LyricLine {
        val text = optString("value")
        val start = (optLong("start", 0L) + offset).coerceAtLeast(0L)
        val end = (optLong("end", start) + offset).coerceAtLeast(start)
        val words = values(this, "cue").mapNotNull { cue ->
            val value = cue.optString("value")
            if (value.isBlank()) return@mapNotNull null
            val wordStart = (cue.optLong("start", start) + offset).coerceAtLeast(0L)
            val wordEnd = (cue.optLong("end", wordStart) + offset).coerceAtLeast(wordStart)
            LyricWord(wordStart, wordEnd, value)
        }
        return LyricLine(
            timeMs = start,
            text = text,
            words = words,
            sungUntilMs = end.takeIf { it > start },
            background = background,
            alignment = alignment,
        )
    }

    private fun values(parent: JSONObject?, key: String): List<JSONObject> = when (val raw = parent?.opt(key)) {
        is JSONArray -> List(raw.length()) { raw.optJSONObject(it) }.filterNotNull()
        is JSONObject -> listOf(raw)
        else -> emptyList()
    }
}
