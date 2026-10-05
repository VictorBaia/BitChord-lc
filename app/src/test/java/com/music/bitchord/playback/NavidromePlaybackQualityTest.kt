package com.music.bitchord.playback

import com.music.bitchord.data.navidrome.NavidromeConfig
import com.music.bitchord.data.navidrome.NavidromeStreamQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class NavidromePlaybackQualityTest {
    @Test
    fun unopenedTrackUsesSettingsAtLoadNotAtQueueCreation() {
        val playing = NavidromePlaybackQuality()
        val upcoming = NavidromePlaybackQuality()
        assertEquals(NavidromeStreamQuality.AAC_192, playing.select(NavidromeStreamQuality.AAC_192, null))
        assertEquals(NavidromeStreamQuality.ORIGINAL, upcoming.select(NavidromeStreamQuality.ORIGINAL, null))
        assertEquals(NavidromeStreamQuality.AAC_192, playing.select(NavidromeStreamQuality.ORIGINAL, null))
    }

    @Test
    fun preloadedTrackAndItsSeekKeepTheirRendition() {
        val upcoming = NavidromePlaybackQuality()
        assertEquals(NavidromeStreamQuality.AAC_320, upcoming.select(NavidromeStreamQuality.AAC_320, null))
        assertEquals(NavidromeStreamQuality.AAC_320, upcoming.select(NavidromeStreamQuality.MP3_128, null))
    }

    @Test
    fun existingCachedRenditionIsPreserved() {
        val track = NavidromePlaybackQuality()
        assertEquals(NavidromeStreamQuality.AAC_256, track.select(NavidromeStreamQuality.ORIGINAL, NavidromeStreamQuality.AAC_256))
        assertEquals(NavidromeStreamQuality.AAC_256, track.select(NavidromeStreamQuality.MP3_320, null))
    }

    @Test
    fun unloadedTracksUseTheirActiveNetworkSettings() {
        val config = NavidromeConfig(
            wifiStreamQuality = NavidromeStreamQuality.ORIGINAL,
            cellularStreamQuality = NavidromeStreamQuality.AAC_128,
        )
        assertEquals(NavidromeStreamQuality.ORIGINAL, NavidromePlaybackQuality().select(config.streamQuality(false), null))
        assertEquals(NavidromeStreamQuality.AAC_128, NavidromePlaybackQuality().select(config.streamQuality(true), null))
    }
}
