package com.music.bitchord.ui.player

import com.music.bitchord.data.lyrics.LyricLine

/** Index of the last line that has started, without walking the whole transcript each frame. */
internal fun latestLyricIndex(lines: List<LyricLine>, positionMs: Long): Int {
    var low = 0
    var high = lines.lastIndex
    var answer = -1
    while (low <= high) {
        val middle = (low + high).ushr(1)
        if (lines[middle].timeMs <= positionMs) {
            answer = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return answer
}

/** Keep every unfinished vocal visible, including overlaps spanning more than two rows. */
internal fun activeLyricRows(lines: List<LyricLine>, positionMs: Long): List<Int> {
    val latest = latestLyricIndex(lines, positionMs)
    if (latest < 0) return emptyList()
    return (0..latest).filter { index ->
        val line = lines[index]
        index == latest || (!line.isGap &&
            (line.hasKnownEnd || line.background?.hasKnownEnd == true) &&
            line.timeMs <= positionMs && positionMs < line.endMs)
    }
}
