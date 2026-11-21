// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.sp
import kotlin.math.sin
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.text.font.FontWeight
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
    highResolutionSource: Boolean = false,
    visualizerVisible: Boolean = true,
    artwork: @Composable (Modifier) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val showSpectrum = state.playing && visualizerVisible
    val radial = LocalSpectrumOptions.current.style == MioSpectrumStyle.RADIAL
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 640.dp
        val coverWidth = if (wide) minOf((maxWidth - 88.dp) / 2, 440.dp)
            else minOf(maxWidth * .84f, maxHeight * .56f, 328.dp).coerceAtLeast(96.dp)
        val controls: @Composable () -> Unit = {
            Column(Modifier.width(coverWidth),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.testTag("mio-track-metadata"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(state.title.ifBlank { "Chưa chọn bài" },
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif, fontSize = 27.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (state.artist.isNotBlank()) Text(state.artist,
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif, fontSize = 17.sp, lineHeight = 20.sp),
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.album.isNotBlank()) Text(state.album,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, lineHeight = 17.sp),
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (state.id != null) {
                    MioSeekBar(state, actions.seek)
                    Row(Modifier.fillMaxWidth().testTag("mio-transport"), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        IconToggleButton(state.shuffled, { actions.shuffle() },
                            colors = IconButtonDefaults.iconToggleButtonColors(checkedContentColor = colors.primary)) {
                            StudioIcon(R.drawable.ic_shuffle, "Phát ngẫu nhiên", Modifier.size(20.dp))
                        }
                        IconButton(onClick = actions.previous) { StudioIcon(R.drawable.ic_previous, "Bài trước", Modifier.size(22.dp)) }
                        OutlinedIconButton(onClick = actions.toggle, modifier = Modifier.size(64.dp),
                            border = BorderStroke(1.5.dp, colors.primary)) {
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
                Row(Modifier.fillMaxWidth().testTag("mio-format"), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    if (specs.isNotEmpty()) {
                        Surface(color = colors.surfaceContainerLow, shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .5f))) {
                            Text(specs.take(3).joinToString("  |  "), Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    IconButton(onClick = actions.sound) {
                        CompositionLocalProvider(LocalContentColor provides colors.primary) {
                            StudioIcon(R.drawable.ic_audio_controls, "Audio Lab", Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
        val cover: @Composable () -> Unit = {
            val frame = RoundedCornerShape(16.dp)
            Box(Modifier.size(coverWidth).testTag("mio-cover").clip(frame).border(1.dp, colors.primary.copy(alpha = .45f), frame)) {
                artwork(Modifier.matchParentSize())
                if (showSpectrum && radial) MioSpectrum(true, Modifier.matchParentSize())
                if (highResolutionSource) Surface(
                    Modifier.align(Alignment.TopStart).padding(14.dp), color = colors.background,
                    shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, colors.primary.copy(alpha = .8f)),
                ) {
                    Text("Hi-Res\nAUDIO", Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                        color = colors.primary, style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp, lineHeight = 11.sp, letterSpacing = 0.sp))
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically) {
                cover()
                Column(Modifier.width(coverWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    controls()
                    if (showSpectrum && !radial) MioSpectrum(true, Modifier.fillMaxWidth().height(42.dp))
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 28.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                cover()
                controls()
                if (state.id != null) {
                    Box(Modifier.fillMaxWidth().height(76.dp).offset(y = (-14).dp), contentAlignment = Alignment.Center) {
                        MioWaveFooter(Modifier.matchParentSize())
                        if (showSpectrum && !radial) MioSpectrum(true, Modifier.width(coverWidth).height(48.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MioWaveFooter(modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Spacer(modifier.drawWithCache {
        val strands = List(15) { line ->
            Path().apply {
                for (step in 0..64) {
                    val t = step / 64f
                    val y = size.height * (.74f - .43f * t +
                        .21f * sin(t * 9.4f + line * .085f) + .017f * line)
                    if (step == 0) moveTo(0f, y) else lineTo(t * size.width, y)
                }
            }
        }
        val ink = Brush.horizontalGradient(0f to Color.Transparent,
            .30f to accent.copy(alpha = .10f), .66f to accent.copy(alpha = .21f),
            .91f to accent.copy(alpha = .085f), 1f to Color.Transparent)
        val stroke = Stroke(.45.dp.toPx())
        onDrawBehind { strands.forEach { drawPath(it, ink, style = stroke) } }
    })
}

@Composable
internal fun MioNowHeader(
    close: () -> Unit, favorite: Boolean, canFavorite: Boolean, toggleFavorite: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Box(Modifier.padding(top = 12.dp)) {
        MioHeader("Mio Player", centered = true, back = close, editorial = true) {
            if (canFavorite) IconToggleButton(favorite, { toggleFavorite() }) {
                StudioIcon(if (favorite) R.drawable.ic_favorite else R.drawable.ic_mio_heart, "Yêu thích bài hát")
            }
            actions()
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
                val inset = 6.dp.toPx()
                val y = center.y
                val x = inset + (size.width - inset * 2) * value
                drawLine(colors.outline.copy(alpha = .5f), Offset(inset, y), Offset(size.width - inset, y),
                    strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                if (value > 0f) drawLine(colors.primary, Offset(inset, y), Offset(x, y),
                    strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(colors.primary, 6.dp.toPx(), Offset(x, y))
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
