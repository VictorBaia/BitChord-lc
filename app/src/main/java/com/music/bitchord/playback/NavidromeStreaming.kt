package com.music.bitchord.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.ts.AdtsExtractor
import java.util.concurrent.ConcurrentHashMap

/** Seek positions are time tokens, never estimates of a transcode's byte offset. */
internal object NavidromeStreaming {
    private const val SEEK_BASE = 1_000_000_000_000L
    private val durationUs = ConcurrentHashMap<String, Long>()

    fun register(id: String, durationSeconds: Int?) {
        if (durationSeconds != null && durationSeconds > 0) {
            durationUs[id] = durationSeconds * 1_000_000L
        }
    }

    fun duration(uri: Uri): Long =
        uri.getQueryParameter("bitchordDurationSec")?.toLongOrNull()?.times(1_000_000L)
            ?: uri.getQueryParameter("d")?.toLongOrNull()?.times(1_000_000L)
            ?: uri.getQueryParameter("id")?.let(durationUs::get)
            ?: C.TIME_UNSET

    fun isSeek(position: Long): Boolean = position >= SEEK_BASE

    fun seekSeconds(position: Long): Long =
        ((position - SEEK_BASE) / 1_000_000L).coerceAtLeast(0)

    /** Actual beginning of the response opened with Subsonic's timeOffset. */
    fun responseStartUs(position: Long): Long =
        if (isSeek(position)) seekSeconds(position) * 1_000_000L else 0L

    private fun seekPosition(timeUs: Long): Long =
        if (timeUs <= 0L) 0L else SEEK_BASE + timeUs

    fun extractorsFactory(): ExtractorsFactory = object : ExtractorsFactory {
        private val other = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)

        override fun createExtractors(): Array<Extractor> = other.createExtractors()

        override fun createExtractors(
            uri: Uri,
            responseHeaders: Map<String, List<String>>,
        ): Array<Extractor> {
            // Cache hits expose the internal URI instead of the resolved HTTP
            // URL. They must use the same time seek map: ADTS's constant-bitrate
            // estimate is not an exact index into Navidrome's generated AAC.
            val internal = uri.authority == "navidrome"
            val format = uri.getQueryParameter(if (internal) "qf" else "format")
            if ((internal || uri.path?.endsWith("/stream.view") == true) &&
                (format == "aac" || format == "mp3")
            ) {
                val extractor = if (format == "aac") AdtsExtractor(0) else Mp3Extractor(0)
                return arrayOf(TimeOffsetExtractor(extractor, duration(uri)))
            }
            return other.createExtractors(uri, responseHeaders)
        }
    }

    private class TimeOffsetExtractor(
        private val delegate: Extractor,
        private val durationUs: Long,
    ) : Extractor {
        private var responseStartUs = 0L
        private var timestampDeltaUs: Long? = null

        override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

        override fun init(output: ExtractorOutput) {
            delegate.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput {
                    val target = output.track(id, type)
                    return object : TrackOutput by target {
                        override fun sampleMetadata(
                            timeUs: Long,
                            flags: Int,
                            size: Int,
                            offset: Int,
                            cryptoData: TrackOutput.CryptoData?,
                        ) {
                            if (timestampDeltaUs == null) {
                                timestampDeltaUs = responseStartUs - timeUs
                            }
                            target.sampleMetadata(
                                timeUs + (timestampDeltaUs ?: 0L),
                                flags, size, offset, cryptoData,
                            )
                        }
                    }
                }

                override fun endTracks() = output.endTracks()

                override fun seekMap(seekMap: SeekMap) {
                    // Inside the anonymous SeekMap, an unqualified `durationUs`
                    // resolves to its own synthetic Kotlin property
                    // (`getDurationUs()`), not TimeOffsetExtractor.durationUs.
                    // Capturing the outer value prevents the getter from calling
                    // itself until ExoPlayer's playback thread overflows.
                    val streamDurationUs = this@TimeOffsetExtractor.durationUs
                    output.seekMap(object : SeekMap {
                        override fun isSeekable(): Boolean = streamDurationUs > 0L
                        override fun getDurationUs(): Long = streamDurationUs
                        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                            val bounded = if (streamDurationUs > 0L) {
                                timeUs.coerceIn(0L, streamDurationUs - 1L)
                            } else {
                                0L
                            }
                            val wholeSecond = bounded / 1_000_000L * 1_000_000L
                            return SeekMap.SeekPoints(SeekPoint(wholeSecond, seekPosition(wholeSecond)))
                        }
                    })
                }
            })
        }

        override fun seek(position: Long, timeUs: Long) {
            // Media3 passes the requested time here, which can be fractional,
            // but the response starts at the whole second encoded in position.
            // Label samples with that actual start so Media3 can discard audio
            // preceding timeUs. Labeling the first sample as timeUs instead
            // played that preceding audio while lyrics already showed timeUs.
            // Position zero also covers seeks within the first second.
            responseStartUs = NavidromeStreaming.responseStartUs(position)
            timestampDeltaUs = null
            delegate.seek(position, timeUs)
        }

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
            delegate.read(input, seekPosition)

        override fun release() = delegate.release()
    }
}
