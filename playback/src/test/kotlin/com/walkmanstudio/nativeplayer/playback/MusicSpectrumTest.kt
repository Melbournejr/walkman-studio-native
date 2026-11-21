// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class MusicSpectrumTest {
    @Test fun silenceIsFlatAndHigherTonesMoveThePeakRight() {
        val rate = 48000
        fun peak(hz: Int): Int {
            val samples = FloatArray(1024) { (.7 * sin(2 * PI * hz * it / rate)).toFloat() }
            val bands = spectrumBands(samples, rate)
            val peak = bands.indices.maxBy { bands[it] }
            assertTrue(bands[peak] > .8f)
            return peak
        }
        assertTrue(spectrumBands(FloatArray(1024), rate).all { it == 0f })
        assertTrue(peak(160) < peak(1600))
        assertTrue(peak(1600) < peak(6400))
    }

    @Test fun consumesOnlyAcceptedPcmAndLeavesBufferCursorAndBytesAlone() = runBlocking {
        val tap = PcmSpectrumTap(C.ENCODING_PCM_16BIT, 48000, 2)
        val match = launch(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(3000L) {
                val spectrum = MusicSpectrum.bands.first { it.isNotEmpty() }
                assertTrue(spectrum.any { it > .8f })
            }
        }
        val buffer = ByteBuffer.allocate(4100).order(ByteOrder.LITTLE_ENDIAN)
        repeat(1025) { frame ->
            val sample = (16000 * sin(2 * PI * 600 * frame / 48000)).toInt().toShort()
            buffer.putShort(sample).putShort((-sample).toShort())
        }
        val original = buffer.array().copyOf()
        buffer.position(1001)
        tap.written(buffer, 0)
        assertEquals(1001, buffer.position())
        assertTrue(MusicSpectrum.bands.value.isEmpty())
        buffer.position(buffer.limit())
        tap.written(buffer, 1001)
        assertEquals(buffer.limit(), buffer.position())
        assertArrayEquals(original, buffer.array())
        match.join()
        tap.reset()
        assertTrue(MusicSpectrum.bands.value.isEmpty())
        match.cancelAndJoin()
    }
}
