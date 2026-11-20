// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MioNowPlaying(
    state: PlaybackUi,
    specs: List<String>,
    actions: CassetteActions,
    volume: @Composable () -> Unit,
    sleep: @Composable () -> Unit,
    artwork: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 640.dp
        val coverWidth = if (wide) minOf((maxWidth - 80.dp) / 2, 440.dp)
            else minOf(maxWidth - MioSpacing.gutter * 2, maxHeight * .50f, 440.dp).coerceAtLeast(96.dp)
        val controls: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(MioSpacing.group)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.title.ifBlank { "Chưa chọn bài" }, style = MaterialTheme.typography.headlineMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    state.artist.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    state.album.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (specs.isNotEmpty()) Text(specs.take(4).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (state.id != null) {
                    MioSeekBar(state, actions.seek)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically) {
                        IconToggleButton(state.shuffled, { actions.shuffle() }) { StudioIcon(R.drawable.ic_shuffle, "Phát ngẫu nhiên") }
                        IconButton(onClick = actions.previous) { StudioIcon(R.drawable.ic_previous, "Bài trước") }
                        FilledIconButton(onClick = actions.toggle, modifier = Modifier.size(64.dp)) {
                            StudioIcon(if (state.requested) R.drawable.ic_pause else R.drawable.ic_play,
                                if (state.requested) "Tạm dừng" else "Phát", Modifier.size(30.dp))
                        }
                        IconButton(onClick = actions.next) { StudioIcon(R.drawable.ic_next, "Bài tiếp") }
                        IconToggleButton(state.repeatOne, { actions.repeat() }) { StudioIcon(R.drawable.ic_repeat, "Lặp một bài") }
                    }
                }
                if (state.error != null) {
                    Text("Chưa đọc được bài hát", color = MaterialTheme.colorScheme.error)
                    Row {
                        TextButton(onClick = actions.retry) { Text("Thử lại") }
                        TextButton(onClick = actions.details) { Text("Chi tiết") }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MioSpacing.compact)) {
                    TextButton(onClick = actions.queue) {
                        StudioIcon(R.drawable.ic_queue)
                        Spacer(Modifier.width(MioSpacing.compact))
                        Text("Hàng đợi")
                    }
                    TextButton(onClick = actions.sound) {
                        StudioIcon(R.drawable.ic_audio_controls)
                        Spacer(Modifier.width(MioSpacing.compact))
                        Text("Audio Lab")
                    }
                    if (state.id != null) {
                        volume()
                        sleep()
                    }
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MioSpacing.gutter),
                horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                artwork(Modifier.size(coverWidth))
                Box(Modifier.weight(1f)) { controls() }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MioSpacing.gutter),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
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
    val preview = dragging?.let { (state.durationMs * it).toLong() } ?: state.positionMs
    Column {
        Slider(value = dragging ?: progress, onValueChange = { dragging = it }, enabled = state.seekEnabled,
            onValueChangeFinished = {
                dragging?.takeIf { state.seekEnabled }?.let { seek((state.durationMs * it).toLong()) }
                dragging = null
            },
            modifier = Modifier.semantics { contentDescription = "Tua bài hát" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clockTime(preview), style = MaterialTheme.typography.labelMedium)
            Text(if (state.durationMs > 0) clockTime(state.durationMs) else "–:–",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
