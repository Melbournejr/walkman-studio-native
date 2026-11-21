// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.isActive

/** All geometry uses the original 941 × 1672 cassette artwork coordinate space. */
internal object StudioTape {
    const val CORE = 111f
    const val FULL = 176f
    private const val AREA = FULL * FULL - CORE * CORE
    private const val TAPE_UNITS_PER_SECOND = 360f

    fun packs(positionMs: Double, durationMs: Long): TapePacks {
        val p = if (durationMs > 0) (positionMs / durationMs).coerceIn(0.0, 1.0) else 0.0
        val left = sqrt(CORE * CORE + AREA * (1.0 - p))
        val right = sqrt(CORE * CORE + AREA * p)
        // Integrate angular speed v/r along the timeline, so seeking restores the same phases.
        val turns = durationMs.coerceAtLeast(0) / 1000.0 * (360 / Math.PI) * TAPE_UNITS_PER_SECOND / AREA
        return TapePacks(left.toFloat(), right.toFloat(),
            (-(FULL - left) * turns % 360).toFloat(), (-(right - CORE) * turns % 360).toFloat())
    }

    fun tangent(center: Offset, radius: Float, guide: Offset): Offset {
        val delta = guide - center
        val distanceSquared = delta.x * delta.x + delta.y * delta.y
        val near = radius * radius / distanceSquared
        val perpendicular = radius * sqrt(distanceSquared - radius * radius) / distanceSquared
        val lower = if (delta.x > 0) Offset(-delta.y, delta.x) else Offset(delta.y, -delta.x)
        return center + delta * near + lower * perpendicular
    }
}

internal data class TapePacks(val left: Float, val right: Float, val leftAngle: Float, val rightAngle: Float)

@Composable
internal fun rememberStudioTape(track: String, positionMs: Long, durationMs: Long, running: Boolean): State<TapePacks> {
    val rendered = remember(track, durationMs) { mutableStateOf(StudioTape.packs(positionMs.toDouble(), durationMs)) }
    val displayedPosition = remember(track, durationMs) { mutableStateOf(positionMs.toDouble()) }
    LaunchedEffect(track, positionMs, durationMs, running) {
        // Keep small clock corrections smooth between controller updates. Large jumps are seeks.
        val last = displayedPosition.value
        val startPosition = if (running && abs(last - positionMs) <= 95) maxOf(last, positionMs.toDouble())
            else positionMs.toDouble()
        displayedPosition.value = startPosition
        rendered.value = StudioTape.packs(startPosition, durationMs)
        if (!running || durationMs <= 0) return@LaunchedEffect
        val ceiling = minOf(durationMs.toDouble(), positionMs + 600.0)
        val startFrame = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { frame ->
                val next = (startPosition + (frame - startFrame) / 1_000_000.0)
                    .coerceAtMost(ceiling)
                displayedPosition.value = next
                rendered.value = StudioTape.packs(next, durationMs)
            }
            if (displayedPosition.value >= ceiling) break
        }
    }
    return rendered
}
