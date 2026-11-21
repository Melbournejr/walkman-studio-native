// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkmanstudio.nativeplayer.playback.MusicSpectrum

@Composable
internal fun MioSpectrum(playing: Boolean, modifier: Modifier = Modifier) {
    val levels = if (playing) {
        val live by MusicSpectrum.bands.collectAsStateWithLifecycle()
        live
    } else emptyList()
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier.semantics { contentDescription = "Phổ tần âm nhạc" }) {
        val bars = 24
        val step = size.width / bars
        val brush = Brush.verticalGradient(listOf(accent, accent.copy(alpha = .48f)))
        for (index in 0 until bars) {
            val level = levels.getOrElse(index) { 0f }
            val baseline = minOf(2.dp.toPx(), size.height)
            val height = baseline + level * (size.height - baseline)
            val x = index * step
            drawRoundRect(accent.copy(alpha = .20f), Offset(x, size.height - baseline),
                Size(step * .68f, baseline), CornerRadius(1.dp.toPx()))
            if (level > 0f) drawRoundRect(brush, Offset(x, size.height - height),
                Size(step * .68f, height), CornerRadius(1.dp.toPx()))
        }
    }
}
