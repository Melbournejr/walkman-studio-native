// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.*
import com.walkmanstudio.nativeplayer.core.DsdTestFiles
import com.walkmanstudio.nativeplayer.playback.dsd.DsdExtractor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@androidx.annotation.OptIn(UnstableApi::class)
class DsdLevelIntegrationTest {
    @Test fun dsfAndDffAllRatesGainOnlyNativeFloatOutputAndDefaultRemainsByteExact() {
        for (rate in listOf(2822400, 5644800, 11289600)) for (channels in 1..2) {
            val frames = 2200
            val planes = Array(channels) { channel -> ByteArray(frames * (rate / 88200 / 8)) { index ->
                // Both positive and negative full-scale plateaus, with a non-integer low-level segment.
                when ((index / (rate / 88200 / 8) + channel * 137) % 1000) {
                    in 0..299 -> 0xFF.toByte()
                    in 300..599 -> 0
                    else -> (index * 37 + channel * 53).toByte()
                }
            } }
            val containers = listOf(DsdTestFiles.dsf(rate, planes), DsdTestFiles.dff(rate, planes.map { p -> p.map(DsdTestFiles::reverseBits).toByteArray() }.toTypedArray()))
            for (source in containers) {
                val baseline = decode(source)
                assertArrayEquals(baseline, decode(source, DsdLevelState(0)))
                for (db in listOf(3, 6)) {
                    val level = DsdLevelState(db)
                    val gained = decode(source, level)
                    assertEquals(frames * channels * Float.SIZE_BYTES, gained.size)
                    val plain = ByteBuffer.wrap(baseline).order(ByteOrder.nativeOrder())
                    val actual = ByteBuffer.wrap(gained).order(ByteOrder.nativeOrder())
                    val multiplier = 10.0.pow(db / 20.0).toFloat()
                    var exceeded = 0L
                    while (plain.hasRemaining()) {
                        val expected = plain.float * multiplier
                        val sample = actual.float
                        assertEquals(expected, sample, 0f)
                        if (abs(sample) > 1f) exceeded++
                    }
                    assertTrue("Synthetic DSD should exceed full scale at +$db dB", exceeded > 0)
                    assertEquals(exceeded, level.overFullScaleSamples)
                }
            }
        }
    }

    private fun decode(bytes: ByteArray, level: DsdLevelState? = null): ByteArray {
        val extractor = if (level == null) DsdExtractor() else DsdExtractor(levelState = level)
        val pcm = ByteArrayOutputStream()
        extractor.init(object: ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                assertEquals(C.TRACK_TYPE_AUDIO, type)
                return object: TrackOutput {
                    override fun format(format: Format) { assertEquals(C.ENCODING_PCM_FLOAT, format.pcmEncoding) }
                    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int = throw AssertionError("Expected float bytes")
                    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                        val block = ByteArray(length); data.readBytes(block, 0, length); pcm.write(block)
                    }
                    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) { }
                }
            }
            override fun endTracks() { }
            override fun seekMap(seekMap: SeekMap) { }
        })
        fun input(position: Long): ExtractorInput {
            var at = position.toInt()
            return DefaultExtractorInput(DataReader { destination, offset, length ->
                if (at >= bytes.size) -1 else {
                    val count = minOf(length, bytes.size - at, 97)
                    bytes.copyInto(destination, offset, at, at + count); at += count; count
                }
            }, position, bytes.size.toLong())
        }
        return try {
            var source = input(0)
            val seek = PositionHolder()
            repeat(10000) {
                when (extractor.read(source, seek)) {
                    Extractor.RESULT_END_OF_INPUT -> return pcm.toByteArray()
                    Extractor.RESULT_SEEK -> source = input(seek.position)
                }
            }
            throw AssertionError("Bounded synthetic decode did not complete")
        } finally { extractor.release() }
    }
}
