// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.compose.runtime.*
import kotlinx.coroutines.isActive

/** A finite linear coast, never background catch-up or an invisible frame loop. */
internal object CassetteCoast {
    const val DURATION_MS = 150.0
    fun fraction(elapsedMs: Double): Double = (elapsedMs / DURATION_MS).coerceIn(0.0, 1.0)
    fun speed(initialDegreesPerSecond: Double, elapsedMs: Double): Double =
        initialDegreesPerSecond * (1.0 - fraction(elapsedMs))
    fun angle(initialDegreesPerSecond: Double, elapsedMs: Double): Double {
        val seconds = fraction(elapsedMs) * DURATION_MS / 1000.0
        return initialDegreesPerSecond * (seconds - seconds * seconds / (2.0 * DURATION_MS / 1000.0))
    }
}

@Composable
internal fun cassetteDeckRotation(playing: Boolean, seeking: Boolean, visible: Boolean): State<Double> {
    val allowed = transportAnimationAllowed(true, false) && visible
    val angle = remember { mutableDoubleStateOf(0.0) }
    val speed = remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(playing, seeking, allowed) {
        if (!allowed || seeking) {
            speed.doubleValue = 0.0
            return@LaunchedEffect
        }
        if (playing) {
            speed.doubleValue = TransportGeometry.VINYL_DEGREES_PER_SECOND.toDouble()
            var previous = withFrameNanos { it }
            while (isActive) {
                withFrameNanos { now ->
                    angle.doubleValue += (now - previous) / 1_000_000_000.0 * speed.doubleValue
                    previous = now
                }
            }
        } else if (speed.doubleValue > 0.0) {
            val initialSpeed = speed.doubleValue
            val initialAngle = angle.doubleValue
            val start = withFrameNanos { it }
            do {
                val elapsedMs = withFrameNanos { (it - start) / 1_000_000.0 }
                angle.doubleValue = initialAngle + CassetteCoast.angle(initialSpeed, elapsedMs)
                speed.doubleValue = CassetteCoast.speed(initialSpeed, elapsedMs)
            } while (elapsedMs < CassetteCoast.DURATION_MS && isActive)
        }
    }
    return angle
}
