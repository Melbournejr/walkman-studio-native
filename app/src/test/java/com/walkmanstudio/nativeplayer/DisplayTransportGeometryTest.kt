// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class DisplayTransportGeometryTest {
    @Test fun displayedTapeNeverReachesAnEmptyHubOrTouchesTheWindow() {
        var previousSource = Float.POSITIVE_INFINITY
        var previousTarget = 0f
        val conservedArea = .43f * .43f + .86f * .86f
        for (step in 0..1000) {
            val (source, target) = TransportGeometry.displayTapeRadii(step / 1000f, true)
            assertTrue(source in .43f.. .860001f)
            assertTrue(target in .43f.. .860001f)
            assertTrue(source <= previousSource)
            assertTrue(target >= previousTarget)
            assertEquals(conservedArea, source * source + target * target, .000001f)
            previousSource = source
            previousTarget = target
        }
    }

    @Test fun unknownAndNonFiniteProgressKeepTheTwoPacksBalanced() {
        for (progress in listOf(-1f, 0f, .3f, 1f, 100f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val (left, right) = TransportGeometry.displayTapeRadii(progress, false)
            assertEquals(left, right, 0f)
            assertTrue(left in .43f.. .86f)
        }
        val invalid = TransportGeometry.displayTapeRadii(Float.NaN, true)
        assertEquals(invalid.first, invalid.second, 0f)
        assertEquals(TransportGeometry.displayTapeRadii(0f, true), TransportGeometry.displayTapeRadii(-100f, true))
        assertEquals(TransportGeometry.displayTapeRadii(1f, true), TransportGeometry.displayTapeRadii(100f, true))
    }

    @Test fun actualNeedleContactMovesAcrossGroovesWithoutEnteringTheLabel() {
        val center = Offset(180f, 130f)
        val diameter = 220f
        var previousRadius = Float.POSITIVE_INFINITY
        for (step in 0..1000) {
            val point = TransportGeometry.stylusPoint(step / 1000f, center, diameter)
            val delta = point - center
            val radius = sqrt(delta.x * delta.x + delta.y * delta.y)
            assertTrue("Contact must remain between actual inner/outer groove radii", radius in diameter * .21499f..diameter * .45001f)
            assertTrue("Needle must approach the center monotonically", radius <= previousRadius)
            assertTrue(point.x > center.x && point.y < center.y)
            previousRadius = radius
        }
        assertEquals(TransportGeometry.stylusPoint(0f, center, diameter), TransportGeometry.stylusPoint(-5f, center, diameter))
        assertEquals(TransportGeometry.stylusPoint(1f, center, diameter), TransportGeometry.stylusPoint(5f, center, diameter))
    }
}
