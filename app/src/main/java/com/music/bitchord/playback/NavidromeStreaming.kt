package com.music.bitchord.playback

import android.net.Uri
import android.util.Log
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
        uri.getQueryParameter("id")?.let(durationUs::get) ?: C.TIME_UNSET

    fun isSeek(position: Long): Boolean = position >= SEEK_BASE

    fun seekSeconds(position: Long): Long =
        ((position - SEEK_BASE) / 1_000_000L).coerceAtLeast(0)

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
            val format = uri.getQueryParameter("format")
            if (uri.path?.endsWith("/stream.view") == true && (format == "aac" || format == "mp3")) {
                Log.d("TRANSDBG", "factory format=$format durationUs=${duration(uri)}")
                val extractor = if (format == "aac") AdtsExtractor(0) else Mp3Extractor(0)
                return arrayOf(TimeOffsetExtractor(extractor, duration(uri), format == "mp3"))
            }
            return other.createExtractors(uri, responseHeaders)
        }
    }

    private class TimeOffsetExtractor(
        private val delegate: Extractor,
        private val durationUs: Long,
        private val adjustMp3Timestamps: Boolean,
    ) : Extractor {
        private var seekTargetUs = 0L
        private var timestampDeltaUs: Long? = null

        override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

        override fun init(output: ExtractorOutput) {
            Log.d("TRANSDBG", "extractor init durationUs=$durationUs")
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
                            if (adjustMp3Timestamps && timestampDeltaUs == null) {
                                timestampDeltaUs = seekTargetUs - timeUs
                            }
                            target.sampleMetadata(
                                timeUs + (if (adjustMp3Timestamps) timestampDeltaUs ?: 0L else 0L),
                                flags, size, offset, cryptoData,
                            )
                        }
                    }
                }

                override fun endTracks() = output.endTracks()

                override fun seekMap(seekMap: SeekMap) {
                    output.seekMap(object : SeekMap {
                        override fun isSeekable(): Boolean = durationUs > 0L
                        override fun getDurationUs(): Long = durationUs
                        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                            val bounded = if (durationUs > 0L) timeUs.coerceIn(0L, durationUs - 1L) else 0L
                            val wholeSecond = bounded / 1_000_000L * 1_000_000L
                            return SeekMap.SeekPoints(SeekPoint(wholeSecond, seekPosition(wholeSecond)))
                        }
                    })
                }
            })
        }

        override fun seek(position: Long, timeUs: Long) {
            seekTargetUs = timeUs
            timestampDeltaUs = null
            delegate.seek(position, timeUs)
        }

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
            delegate.read(input, seekPosition)

        override fun release() = delegate.release()
    }
}
