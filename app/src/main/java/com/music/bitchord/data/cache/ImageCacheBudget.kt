package com.music.bitchord.data.cache

/** One user-visible disk budget shared by every artwork cache. */
object ImageCacheBudget {
    const val COIL_BYTES = 512L * 1024 * 1024

    fun staticArtworkBytes(totalBytes: Long): Long {
        val persistent = (totalBytes - COIL_BYTES).coerceAtLeast(0L)
        return persistent * 2L / 3L
    }

    fun animatedArtworkBytes(totalBytes: Long): Long {
        val persistent = (totalBytes - COIL_BYTES).coerceAtLeast(0L)
        return persistent - staticArtworkBytes(totalBytes)
    }
}
