// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.walkmanstudio.nativeplayer.core.DspConfig

private enum class MioEqPreset(val label: String, val gains: List<Double>) {
    FLAT("Phẳng", List(10) { 0.0 }),
    WARM("Ấm", listOf(3.0, 3.0, 2.0, 1.0, 0.0, 0.0, -1.0, -1.0, -2.0, -2.0)),
    CLEAR("Sáng", listOf(-2.0, -1.0, -1.0, 0.0, 0.0, 1.0, 2.0, 3.0, 3.0, 2.0)),
}

@Composable
internal fun MioEqualizer(
    config: DspConfig, close: () -> Unit, change: (DspConfig) -> Unit,
    advanced: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(colors.background).navigationBarsPadding()) {
        MioHeader("Equalizer", back = close) {
            Switch(!config.sourceDirect, { change(config.copy(sourceDirect = !it)) },
                Modifier.padding(end = 16.dp).semantics { contentDescription = "Xử lý DSP (tắt Source Direct)" })
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            EqualizerPlot(config)
            var menu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Preset", style = MaterialTheme.typography.titleSmall)
                Box {
                    OutlinedButton(onClick = { menu = true }, enabled = !config.sourceDirect) {
                        Text(MioEqPreset.entries.firstOrNull { it.gains == config.bands }?.label ?: "Tùy chỉnh")
                        StudioIcon(R.drawable.ic_expand, modifier = Modifier.padding(start = 8.dp).size(16.dp))
                    }
                    DropdownMenu(menu, { menu = false }) {
                        MioEqPreset.entries.forEach { preset ->
                            DropdownMenuItem(text = { Text(preset.label) }, onClick = {
                                change(config.copy(bands = preset.gains))
                                menu = false
                            })
                        }
                    }
                }
            }
            var showBands by remember { mutableStateOf(false) }
            MioPanelRow(R.drawable.ic_audio_controls, "Điều chỉnh EQ", "10 dải · Clear Bass · Preamp") {
                showBands = !showBands
            }
            if (showBands) advanced()
        }
    }
}

@Composable
private fun EqualizerPlot(config: DspConfig) {
    val colors = MaterialTheme.colorScheme
    val ink = if (config.sourceDirect) colors.onSurfaceVariant else colors.primary
    val shape = RoundedCornerShape(12.dp)
    val frequencies = DspConfig.FREQUENCIES
    Row(Modifier.fillMaxWidth().background(colors.surfaceContainerLow, shape)
        .border(1.dp, colors.outlineVariant.copy(alpha = .6f), shape).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.height(202.dp), verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End) {
            listOf("+12", "+6", "0", "−6", "−12").forEach {
                Text(it, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Canvas(Modifier.fillMaxWidth().height(202.dp).semantics {
                contentDescription = if (config.sourceDirect) "EQ bị bỏ qua bởi Source Direct" else "Đường cong gain của mười dải EQ"
            }) {
                val margin = 7.dp.toPx()
                val plotHeight = size.height - margin * 2
                val step = size.width / frequencies.size
                val points = frequencies.indices.map { index ->
                    Offset(step * (index + .5f), margin + plotHeight *
                        (12 - config.bands[index].coerceIn(-12.0, 12.0).toFloat()) / 24)
                }
                for (line in 0..4) {
                    val y = margin + plotHeight * line / 4
                    drawLine(colors.outlineVariant, Offset(0f, y), Offset(size.width, y), if (line == 2) 1.dp.toPx() else .5.dp.toPx())
                }
                points.forEach { drawLine(colors.outlineVariant, Offset(it.x, margin), Offset(it.x, size.height - margin), .5.dp.toPx()) }
                val area = Path().apply {
                    moveTo(points.first().x, size.height)
                    points.forEach { lineTo(it.x, it.y) }
                    lineTo(points.last().x, size.height)
                    close()
                }
                drawPath(area, Brush.verticalGradient(listOf(ink.copy(alpha = .5f), ink.copy(alpha = 0f)), endY = size.height))
                points.zipWithNext().forEach { (a, b) -> drawLine(ink, a, b, 2.dp.toPx()) }
                points.forEach { drawCircle(ink, 4.dp.toPx(), it); drawCircle(colors.background, 1.7.dp.toPx(), it) }
            }
            Row(Modifier.fillMaxWidth()) {
                frequencies.forEach { hz ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(if (hz < 1000) "$hz" else "${hz / 1000}k", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
    }
}
