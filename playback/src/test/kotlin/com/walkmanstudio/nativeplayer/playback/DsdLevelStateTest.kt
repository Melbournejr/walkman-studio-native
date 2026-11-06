// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

class DsdLevelStateTest {
    @Test fun zeroDbPreservesEveryBitAndBufferBoundaries() {
        val bits = intArrayOf(0, Int.MIN_VALUE, 0x3EAAAAAB, 0xBF7FFFFF.toInt(), 0x7FC01234, 0x7F800000)
        val pcm = ByteBuffer.allocateDirect(bits.size * 4).order(ByteOrder.nativeOrder())
        bits.forEachIndexed { index, value -> pcm.putInt(index * 4, value) }
        pcm.position(4)
        val level = DsdLevelState()
        assertEquals(0, level.gainDb)
        assertEquals(0L, level.applyToDsdFloat(pcm, bits.size))
        assertEquals(4, pcm.position())
        assertEquals(bits.size * 4, pcm.limit())
        assertEquals(0L, level.overFullScaleSamples)
        bits.forEachIndexed { index, value -> assertEquals(value, pcm.getInt(index * 4)) }
    }

    @Test fun gainIsFloatOnlyLeavesHeadroomUnclippedAndCountsChannelsStrictlyAboveFullScale() {
        for (db in listOf(3, 6)) {
            val level = DsdLevelState(db)
            val values = floatArrayOf(0f, 0.1f, -0.1f, 0.9f, -0.9f, 1f, -1f, 123.25f)
            val pcm = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            values.forEachIndexed { index, value -> pcm.putFloat(index * 4, value) }
            val multiplier = 10.0.pow(db / 20.0).toFloat()
            assertEquals(4L, level.applyToDsdFloat(pcm, values.size - 1))
            assertEquals(4L, level.overFullScaleSamples)
            for (index in 0 until values.lastIndex) {
                assertEquals(values[index] * multiplier, pcm.getFloat(index * 4), 0f)
            }
            assertEquals(123.25f, pcm.getFloat(values.lastIndex * 4), 0f)
            assertTrue(pcm.getFloat(5 * 4) > 1f)
            assertTrue(pcm.getFloat(6 * 4) < -1f)
            // Find the representable input whose product is exactly +/-1, not above it.
            val boundary = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
            boundary.putFloat(0, 1f / multiplier).putFloat(4, -1f / multiplier)
            assertEquals(0L, level.applyToDsdFloat(boundary, 2))
            assertEquals(1f, boundary.getFloat(0), 0f)
            assertEquals(-1f, boundary.getFloat(4), 0f)
            assertEquals(4L, level.overFullScaleSamples)
        }
    }

    @Test fun gainChangeResetsCounterButRepeatedSettingDoesNot() {
        val level = DsdLevelState(3)
        fun block() = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).apply { putFloat(0, 0.9f) }
        level.applyToDsdFloat(block(), 1)
        level.setGainDb(3)
        assertEquals(1L, level.overFullScaleSamples)
        level.applyToDsdFloat(block(), 1)
        assertEquals(2L, level.overFullScaleSamples)
        level.setGainDb(6)
        assertEquals(0L, level.overFullScaleSamples)
        level.applyToDsdFloat(block(), 1)
        assertEquals(1L, level.overFullScaleSamples)
        level.setGainDb(0)
        assertEquals(0L, level.overFullScaleSamples)
        assertEquals(0L, level.applyToDsdFloat(block(), 1))
    }

    @Test fun invalidGainAndOutOfBoundsSampleWindowsAreRejectedWithoutChangingState() {
        val level = DsdLevelState(3)
        for (bad in listOf(Int.MIN_VALUE, -6, -3, 1, 2, 4, 7, Int.MAX_VALUE)) {
            try { level.setGainDb(bad); fail("Invalid DSD gain accepted: $bad") } catch (_: IllegalArgumentException) { }
            assertEquals(3, level.gainDb)
            try { DsdLevelState(bad); fail("Invalid initial DSD gain accepted: $bad") } catch (_: IllegalArgumentException) { }
        }
        val pcm = ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder()).apply { limit(8) }
        for (count in listOf(-1, 3, Int.MAX_VALUE)) {
            try { level.applyToDsdFloat(pcm, count); fail("Invalid sample count accepted: $count") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0L, level.overFullScaleSamples)
    }
}
