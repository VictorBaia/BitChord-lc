package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.ForwardingTimeline
import com.music.bitchord.data.sources.TrackMatcher

/** A chunked file response is still an on-demand song, never a live broadcast. */
internal class NavidromeSessionTimeline(timeline: Timeline) : ForwardingTimeline(timeline) {
    override fun getWindow(
        windowIndex: Int,
        window: Timeline.Window,
        defaultPositionProjectionUs: Long,
    ): Timeline.Window {
        super.getWindow(windowIndex, window, defaultPositionProjectionUs)
        if (!window.mediaItem.mediaId.startsWith("nd:")) return window

        // ProgressiveMediaPeriod can classify an unknown-length response as
        // live before an extractor discovers its duration. Media3's legacy
        // session then publishes UNKNOWN position and speed=0, hiding progress.
        window.liveConfiguration = null
        window.isDynamic = false
        if (window.durationUs == C.TIME_UNSET) {
            val metadata = window.mediaItem.mediaMetadata
            val durationMs = metadata.durationMs?.takeIf { it > 0L }
                ?: metadata.extras?.getString(EXTRA_DURATION)
                    ?.let(TrackMatcher::secondsOf)?.takeIf { it > 0 }?.times(1_000L)
            if (durationMs != null) window.durationUs = durationMs * 1_000L
        }
        return window
    }

    override fun getPeriod(
        periodIndex: Int,
        period: Timeline.Period,
        setIds: Boolean,
    ): Timeline.Period {
        super.getPeriod(periodIndex, period, setIds)
        if (period.durationUs != C.TIME_UNSET) return period
        val window = getWindow(period.windowIndex, Timeline.Window(), 0L)
        // Progressive files have a single period. Keep its duration consistent
        // with the window; do not invent boundaries for multi-period sources.
        if (window.mediaItem.mediaId.startsWith("nd:") &&
            window.firstPeriodIndex == window.lastPeriodIndex &&
            window.durationUs != C.TIME_UNSET
        ) {
            period.durationUs = (window.durationUs - period.positionInWindowUs).coerceAtLeast(0L)
        }
        return period
    }
}
