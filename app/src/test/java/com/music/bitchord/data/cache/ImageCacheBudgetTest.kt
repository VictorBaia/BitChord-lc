package com.music.bitchord.data.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCacheBudgetTest {
    @Test
    fun allocationsNeverExceedTheVisibleLimit() {
        val limits = listOf(2L, 4L, 8L, 12L).map { it * 1024 * 1024 * 1024 }
        limits.forEach { total ->
            val allocated = ImageCacheBudget.COIL_BYTES +
                ImageCacheBudget.staticArtworkBytes(total) +
                ImageCacheBudget.animatedArtworkBytes(total)
            assertEquals(total, allocated)
            assertTrue(ImageCacheBudget.staticArtworkBytes(total) >= 0L)
            assertTrue(ImageCacheBudget.animatedArtworkBytes(total) >= 0L)
        }
    }
}
