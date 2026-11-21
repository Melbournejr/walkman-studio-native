// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class StudioTapeAndSpectrumTest {
    @Test fun cassetteTransfersAConstantAreaBetweenTheStartAndEnd() {
        val duration = 240000L
        val start = StudioTape.packs(0.0, duration)
        val middle = StudioTape.packs(duration / 2.0, duration)
        val end = StudioTape.packs(duration.toDouble(), duration)
        assertEquals(StudioTape.FULL, start.left, 0f)
        assertEquals(StudioTape.CORE, start.right, 0f)
        assertEquals(middle.left, middle.right, 0f)
        assertEquals(start.left, end.right, 0f)
        assertEquals(start.right, end.left, 0f)
        for (step in 0..100) {
            val tape = StudioTape.packs(duration * step / 100.0, duration)
            assertEquals(start.left * start.left + start.right * start.right,
                tape.left * tape.left + tape.right * tape.right, .02f)
        }
        val firstSecond = StudioTape.packs(40.0, duration)
        assertTrue(abs(firstSecond.rightAngle) > abs(firstSecond.leftAngle))
    }

    @Test fun tapePathLeavesTheReelAtARealTangent() {
        val center = Offset(275f, 613f)
        val guide = Offset(444f, 719f)
        for (radius in listOf(StudioTape.CORE, StudioTape.FULL)) {
            val contact = StudioTape.tangent(center, radius, guide)
            val radial = contact - center
            val path = guide - contact
            assertEquals(radius, radial.getDistance(), .001f)
            assertEquals(0f, radial.x * path.x + radial.y * path.y, .01f)
        }
    }

    @Test fun visualizerEasingUsesElapsedTimeAndHoldsPeaks() {
        fun advance(fps: Int): SpectrumMotion = SpectrumMotion().apply {
            repeat(fps / 2) { update(listOf(.8f), 1f / fps) }
        }
        val sixty = advance(60)
        val oneTwenty = advance(120)
        assertEquals(sixty.levels[0], oneTwenty.levels[0], .0001f)
        val peak = sixty.peaks[0]
        repeat(6) { sixty.update(emptyList(), 1f / 60) }
        assertTrue(sixty.levels[0] < peak)
        assertEquals(peak, sixty.peaks[0], .0001f)
        repeat(30) { sixty.update(emptyList(), 1f / 60) }
        assertTrue(sixty.peaks[0] < peak)
        assertEquals(0f, sixty.levels[1], 0f)
    }
}
