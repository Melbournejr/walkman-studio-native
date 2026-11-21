// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import android.view.Window
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkmanstudio.nativeplayer.playback.MusicSpectrum
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlinx.coroutines.isActive

internal enum class MioSpectrumStyle(val label: String) {
    BARS("Spectrum Bars"), MIRROR("Mirror Spectrum"), RADIAL("Radial Spectrum")
}

internal data class MioSpectrumOptions(val style: MioSpectrumStyle = MioSpectrumStyle.BARS, val fps: Int = 60)
internal val LocalSpectrumOptions = staticCompositionLocalOf { MioSpectrumOptions() }

/** A display hint. Android may select a lower refresh rate for this window. */
@Composable
internal fun SpectrumRefreshRate(window: Window?, fps: Int, active: Boolean) {
    DisposableEffect(window, fps, active) {
        val target = window?.takeIf { active }
        val previous = target?.attributes?.preferredRefreshRate
        val display = target?.decorView?.display
        if (target != null && display != null) {
            val mode = display.mode
            val selected = display.supportedModes.filter {
                it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight && it.refreshRate <= fps + 1f
            }.maxByOrNull { it.refreshRate }
            if (selected != null) target.attributes = target.attributes.apply { preferredRefreshRate = selected.refreshRate }
        }
        onDispose {
            if (target != null && previous != null) {
                target.attributes = target.attributes.apply { preferredRefreshRate = previous }
            }
        }
    }
}

internal class SpectrumMotion {
    val levels = FloatArray(24)
    val peaks = FloatArray(24)
    private val held = FloatArray(24)

    fun update(target: List<Float>, seconds: Float) {
        val attack = 1f - exp(-seconds / .033f)
        val release = 1f - exp(-seconds / .150f)
        for (i in levels.indices) {
            val value = target.getOrElse(i) { 0f }
            val blend = if (value > levels[i]) attack else release
            levels[i] += (value - levels[i]) * blend

            if (levels[i] >= peaks[i]) {
                peaks[i] = levels[i]
                held[i] = .18f
            } else if (held[i] > 0f) {
                held[i] -= seconds
            } else {
                peaks[i] = maxOf(levels[i], peaks[i] - seconds * 1.9f)
            }
        }
    }
}

private val directions = List(24) { i ->
    val angle = 2 * PI * i / 24 - PI / 2
    Offset(cos(angle).toFloat(), sin(angle).toFloat())
}

@Composable
internal fun MioSpectrum(playing: Boolean, modifier: Modifier = Modifier) {
    val options = LocalSpectrumOptions.current
    val live = if (playing) MusicSpectrum.bands.collectAsStateWithLifecycle().value else emptyList()
    val target by rememberUpdatedState(live)
    var rendered by remember(playing) { mutableStateOf(SpectrumMotion(), neverEqualPolicy()) }
    LaunchedEffect(playing, live.isNotEmpty(), options.fps) {
        if (!playing || live.isEmpty()) return@LaunchedEffect
        val interval = 1_000_000_000L / options.fps
        var last = withFrameNanos { it }
        var next = last + interval
        while (isActive) {
            withFrameNanos { now ->
                if (now + 1_000_000L >= next) {
                    val motion = rendered
                    motion.update(target, ((now - last) / 1_000_000_000f).coerceIn(0f, .1f))
                    rendered = motion
                    last = now
                    next += interval * ((now + 1_000_000L - next) / interval + 1)
                }
            }
        }
    }

    val accent = MaterialTheme.colorScheme.primary
    val brush = remember(accent) { Brush.verticalGradient(listOf(accent, accent.copy(alpha = .48f))) }
    Canvas(modifier.semantics { contentDescription = "Phổ tần âm nhạc" }) {
        // Only drawing is invalidated at display cadence.
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val motion = rendered
        val levels = motion.levels
        if (options.style == MioSpectrumStyle.RADIAL) {
            val diameter = size.minDimension
            val radius = diameter * .395f
            val stroke = (diameter * .014f).coerceIn(1.25.dp.toPx(), 4.dp.toPx())
            drawCircle(accent.copy(alpha = .25f), (radius - stroke * 1.5f).coerceAtLeast(0f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke * .45f))
            for ((index, direction) in directions.withIndex()) {
                val intensity = levels[index]
                val start = center + direction * radius
                val end = start + direction * (diameter * (.014f + .074f * intensity))
                drawLine(accent.copy(alpha = .45f + .55f * intensity), start, end,
                    strokeWidth = stroke, cap = StrokeCap.Round)
            }
        } else {
            val mirror = options.style == MioSpectrumStyle.MIRROR
            val step = size.width / levels.size
            val baseline = minOf(2.dp.toPx(), size.height)
            val radius = CornerRadius(1.dp.toPx())
            for (index in levels.indices) {
                val level = levels[index]
                val height = baseline + level * (size.height - baseline)
                val x = index * step
                val y = if (mirror) (size.height - height) / 2 else size.height - height
                drawRoundRect(brush, Offset(x, y), Size(step * .68f, height), radius,
                    alpha = if (level > 0f) 1f else .24f)
                if (!mirror && motion.peaks[index] > .02f) {
                    val peakY = (size.height - motion.peaks[index] * size.height).coerceAtLeast(1f)
                    drawLine(accent, Offset(x, peakY), Offset(x + step * .68f, peakY), 1.dp.toPx())
                }
            }
        }
    }
}
