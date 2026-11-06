// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import org.junit.Assert.*
import org.junit.Test

class CassetteMechanicsTest {
    @Test fun advancingPlaybackTurnsBothHubsCounterClockwiseAtEveryPackSize() {
        for (step in 0..100) {
            val (source, receive) = TransportGeometry.tapeRadii(step / 100f)
            for (radius in listOf(source, receive)) {
                val first = CassetteMechanics.counterClockwiseAngle(1.0, radius)
                val next = CassetteMechanics.counterClockwiseAngle(2.0, radius)
                assertTrue("Positive Canvas angles would rotate clockwise", first < 0f)
                assertTrue("Both reels must advance counterclockwise", next < first)
                assertEquals(-2.0 / radius, next.toDouble(), .000001)
            }
        }
    }

    @Test fun fullRevolutionsWrapWithoutChangingTheDirectionOrPackBounds() {
        for (radius in listOf(.32f, .5f, .9f)) {
            val angle = CassetteMechanics.counterClockwiseAngle(360.0 * radius + 1.0, radius)
            assertEquals(-1.0 / radius, angle.toDouble(), .0001)
            assertTrue(angle in -360f..0f)
        }
        assertThrows(IllegalArgumentException::class.java) { CassetteMechanics.counterClockwiseAngle(1.0, 0f) }
        assertThrows(IllegalArgumentException::class.java) { CassetteMechanics.counterClockwiseAngle(Double.NaN, .5f) }
    }
}
