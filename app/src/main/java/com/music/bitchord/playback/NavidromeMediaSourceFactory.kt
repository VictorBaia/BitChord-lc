package com.music.bitchord.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource

/** Publish finite-file timelines before ExoPlayer and MediaSession consume them. */
internal class NavidromeMediaSourceFactory(
    private val delegate: MediaSource.Factory,
) : MediaSource.Factory by delegate {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri ?: return delegate.createMediaSource(mediaItem)
        if (!mediaItem.mediaId.startsWith("nd:") || uri.authority != "navidrome") {
            return delegate.createMediaSource(mediaItem)
        }
        val source = delegate.createMediaSource(mediaItem)
        return object : WrappingMediaSource(source) {
            override fun getInitialTimeline(): Timeline? =
                super.getInitialTimeline()?.let(::NavidromeSessionTimeline)

            override fun onChildSourceInfoRefreshed(newTimeline: Timeline) {
                refreshSourceInfo(NavidromeSessionTimeline(newTimeline))
            }

        }
    }
}
