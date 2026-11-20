// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun MioNowPlaying(
    state: PlaybackUi,
    specs: List<String>,
    actions: CassetteActions,
    favorite: Boolean = false,
    canFavorite: Boolean = false,
    artwork: @Composable (Modifier) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 640.dp
        val coverWidth = if (wide) minOf((maxWidth - 88.dp) / 2, 440.dp)
            else minOf(maxWidth - 56.dp, maxHeight * .57f, 440.dp).coerceAtLeast(96.dp)
        val controls: @Composable () -> Unit = {
            Column(Modifier.width(coverWidth), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(state.title.ifBlank { "Chưa chọn bài" }, style = MaterialTheme.typography.titleLarge,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        listOf(state.artist, state.album).filter(String::isNotBlank).forEach {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (canFavorite) {
                        IconToggleButton(favorite, { actions.favorite() }) {
                            StudioIcon(if (favorite) R.drawable.ic_favorite else R.drawable.ic_mio_heart, "Yêu thích bài hát",
                                Modifier.size(21.dp))
                        }
                    }
                }
                if (state.id != null) {
                    MioSeekBar(state, actions.seek)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        IconToggleButton(state.shuffled, { actions.shuffle() },
                            colors = IconButtonDefaults.iconToggleButtonColors(checkedContentColor = colors.primary)) {
                            StudioIcon(R.drawable.ic_shuffle, "Phát ngẫu nhiên", Modifier.size(20.dp))
                        }
                        IconButton(onClick = actions.previous) { StudioIcon(R.drawable.ic_previous, "Bài trước", Modifier.size(22.dp)) }
                        OutlinedIconButton(onClick = actions.toggle, modifier = Modifier.size(56.dp),
                            border = BorderStroke(1.dp, colors.primary)) {
                            StudioIcon(if (state.requested) R.drawable.ic_pause else R.drawable.ic_play,
                                if (state.requested) "Tạm dừng" else "Phát", Modifier.size(27.dp))
                        }
                        IconButton(onClick = actions.next) { StudioIcon(R.drawable.ic_next, "Bài tiếp", Modifier.size(22.dp)) }
                        IconToggleButton(state.repeatOne, { actions.repeat() },
                            colors = IconButtonDefaults.iconToggleButtonColors(checkedContentColor = colors.primary)) {
                            StudioIcon(R.drawable.ic_repeat, "Lặp một bài", Modifier.size(20.dp))
                        }
                    }
                }
                if (state.error != null) {
                    Text("Chưa đọc được bài hát", color = colors.error)
                    Row {
                        TextButton(onClick = actions.retry) { Text("Thử lại") }
                        TextButton(onClick = actions.details) { Text("Chi tiết") }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (specs.isNotEmpty()) {
                        Surface(color = colors.surfaceContainerLow, shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .5f))) {
                            Text(specs.take(3).joinToString(" · "), Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = actions.sound) {
                        CompositionLocalProvider(LocalContentColor provides colors.primary) {
                            StudioIcon(R.drawable.ic_audio_controls, "Audio Lab", Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically) {
                artwork(Modifier.size(coverWidth))
                controls()
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                artwork(Modifier.size(coverWidth))
                controls()
            }
        }
    }
}

@Composable
private fun MioSeekBar(state: PlaybackUi, seek: (Long) -> Unit) {
    var dragging by remember(state.id, state.seekEnabled, state.durationMs) { mutableStateOf<Float?>(null) }
    val progress = if (state.timelineKnown && state.durationMs > 0)
        (state.positionMs.toDouble() / state.durationMs).toFloat().coerceIn(0f, 1f) else 0f
    val value = dragging ?: progress
    val preview = dragging?.let { (state.durationMs * it).toLong() } ?: state.positionMs
    val colors = MaterialTheme.colorScheme
    Column {
        Box(Modifier.fillMaxWidth().heightIn(min = MioSpacing.touch), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val inset = 10.dp.toPx()
                val y = center.y
                val x = inset + (size.width - inset * 2) * value
                drawLine(colors.outline.copy(alpha = .5f), Offset(inset, y), Offset(size.width - inset, y),
                    strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                if (value > 0f) drawLine(colors.primary, Offset(inset, y), Offset(x, y),
                    strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(colors.onSurface, 5.dp.toPx(), Offset(x, y))
            }
            Slider(value = value, onValueChange = { dragging = it }, enabled = state.seekEnabled,
                onValueChangeFinished = {
                    dragging?.takeIf { state.seekEnabled }?.let { seek((state.durationMs * it).toLong()) }
                    dragging = null
                },
                colors = SliderDefaults.colors(thumbColor = Color.Transparent, activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent, disabledThumbColor = Color.Transparent,
                    disabledActiveTrackColor = Color.Transparent, disabledInactiveTrackColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Tua bài hát" })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clockTime(preview), style = MaterialTheme.typography.labelSmall)
            Text(if (state.durationMs > 0) clockTime(state.durationMs) else "–:–",
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}
