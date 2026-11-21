// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
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

/** The display clock runs between controller samples. Only actual seeks reset its phase. */
internal class StudioTapeClock {
    private var positionMs = 0.0
    private var lastFrameNs: Long? = null
    private var lastSampleNs = 0L
    private var discontinuity = 0L
    private var speedCorrection = 0.0

    fun frame(nowNs: Long, durationMs: Long, revision: Long, readPosition: () -> Long): Double {
        val previous = lastFrameNs
        lastFrameNs = nowNs
        if (previous == null || revision != discontinuity) {
            discontinuity = revision
            positionMs = readPosition().toDouble()
            speedCorrection = 0.0
            lastSampleNs = nowNs
        } else {
            positionMs += (nowNs - previous).coerceAtLeast(0) / 1_000_000.0 * (1.0 + speedCorrection)
            if (nowNs - lastSampleNs >= 100_000_000L) {
                // Small bounded speed changes converge on the player position without a stop or jump.
                speedCorrection = ((readPosition() - positionMs) / 900.0).coerceIn(-.06, .06)
                lastSampleNs = nowNs
            }
        }
        positionMs = positionMs.coerceIn(0.0, durationMs.toDouble())
        return positionMs
    }
}

@Composable
internal fun rememberStudioTape(
    track: String?, positionMs: Long, durationMs: Long, running: Boolean, discontinuity: Long,
    readPosition: (String) -> Long?,
): State<TapePacks> {
    val rendered = remember(track, durationMs) { mutableStateOf(StudioTape.packs(positionMs.toDouble(), durationMs)) }
    val position = rememberUpdatedState(positionMs)
    val revision = rememberUpdatedState(discontinuity)
    val positionReader = rememberUpdatedState(readPosition)
    LaunchedEffect(track, durationMs, running) {
        if (!running || track == null || durationMs <= 0) return@LaunchedEffect
        val clock = StudioTapeClock()
        val read = { positionReader.value(track) ?: position.value }
        while (isActive) {
            withFrameNanos { now ->
                val current = clock.frame(now, durationMs, revision.value, read)
                rendered.value = StudioTape.packs(current, durationMs)
            }
        }
    }
    LaunchedEffect(positionMs, running) {
        if (!running || track == null) rendered.value = StudioTape.packs(positionMs.toDouble(), durationMs)
    }
    return rendered
}
