// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class TransportGeometryTest {
    @Test fun tapePackAreaIsConservedAndBothReelsRespectTheirHubs() {
        var previousSource = Float.POSITIVE_INFINITY
        var previousTarget = 0f
        for (step in 0..1000) {
            val (source, target) = TransportGeometry.tapeRadii(step / 1000f)
            assertTrue(source in .32f..(.9f + .000001f))
            assertTrue(target in .32f..(.9f + .000001f))
            assertTrue(source <= previousSource)
            assertTrue(target >= previousTarget)
            assertEquals(.9f * .9f + .32f * .32f, source * source + target * target, .000001f)
            previousSource = source; previousTarget = target
        }
        assertEquals(TransportGeometry.tapeRadii(0f).first, TransportGeometry.tapeRadii(1f).second, 0f)
    }

    @Test fun vinylSpeedIsExactlyThirtyThreeAndOneThirdRpm() {
        assertEquals(33.3333333333, TransportGeometry.VINYL_DEGREES_PER_SECOND * 60.0 / 360, .00000001)
    }

    @Test fun armMovesTowardCenterWithoutEnteringLabel() {
        fun distance(progress: Float): Double {
            val a = TransportGeometry.tonearmAngle(progress) * PI / 180
            val x = .88 + -.18 * cos(a) - .42 * sin(a)
            val y = .16 + -.18 * sin(a) + .42 * cos(a)
            return sqrt((x - .46) * (x - .46) + (y - .52) * (y - .52))
        }
        var previous = 1.0
        for (step in 0..100) {
            val radius = distance(step / 100f)
            assertTrue("Needle must stay in groove area", radius in .145.. .435)
            assertTrue(radius <= previous); previous = radius
        }
    }

    @Test fun frameLoopRequiresEveryVisibilityAndMotionCondition() {
        var allowed = 0
        for (flags in 0..63) {
            val playing = flags and 1 != 0
            val seeking = flags and 2 != 0
            val started = flags and 4 != 0
            val interactive = flags and 8 != 0
            val unlocked = flags and 16 != 0
            val enabled = flags and 32 != 0
            val actual = TransportGeometry.animationAllowed(playing, seeking, started, interactive, unlocked, enabled)
            assertEquals(flags == 61, actual)
            if (actual) allowed++
        }
        assertEquals(1, allowed)
    }

    @Test fun unknownDurationAndDragBoundsCannotSeekOutsideSong() {
        assertEquals(0f, TransportGeometry.progress(100, 0), 0f)
        assertEquals(0f, TransportGeometry.progress(-100, 500), 0f)
        assertEquals(1f, TransportGeometry.progress(Long.MAX_VALUE, 500), 0f)
        assertEquals(.5f, TransportGeometry.progress(250, 500), 0f)
        assertEquals(.75f, TransportGeometry.draggedProgress(.5f, 100f, 400f), 0f)
        assertEquals(0f, TransportGeometry.draggedProgress(.1f, -1000f, 400f), 0f)
        assertEquals(1f, TransportGeometry.draggedProgress(.9f, 1000f, 400f), 0f)
        assertEquals(.5f, TransportGeometry.draggedProgress(.5f, 100f, 0f), 0f)
    }

    @Test fun unknownSavedModeFallsBackToClassicWithoutChangingKnownModes() {
        assertEquals(NowPlayingMode.CLASSIC, NowPlayingMode.find("future"))
        NowPlayingMode.entries.forEach { assertEquals(it, NowPlayingMode.find(it.id)) }
    }
}
