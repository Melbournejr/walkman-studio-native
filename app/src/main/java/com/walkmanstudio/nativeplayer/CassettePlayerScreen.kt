// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** UI snapshot only. Playback and audio processing remain in the service. */
data class CassetteScreenState(
    val title: String = "", val artist: String = "", val album: String = "",
    val positionMs: Long = 0, val durationMs: Long = 0,
    val playing: Boolean = false, val requested: Boolean = false,
    val queueIndex: Int = 0, val queueCount: Int = 0,
    val shuffled: Boolean = false, val repeatOne: Boolean = false,
    val favorite: Boolean = false, val specs: List<String> = emptyList(),
    val error: String? = null, val seekEnabled: Boolean = true, val timelineKnown: Boolean = durationMs > 0,
    val playbackState: Int = if (playing || requested) androidx.media3.common.Player.STATE_READY else androidx.media3.common.Player.STATE_IDLE,
)
data class CassetteActions(
    val toggle: () -> Unit = {}, val previous: () -> Unit = {}, val next: () -> Unit = {},
    val favorite: () -> Unit = {}, val queue: () -> Unit = {}, val sound: () -> Unit = {},
    val shuffle: () -> Unit = {}, val repeat: () -> Unit = {}, val seek: (Long) -> Unit = {},
    val retry: () -> Unit = {}, val details: () -> Unit = {},
    val close: () -> Unit = {}, val palette: () -> Unit = {},
)

/** Insets are supplied by the containing Scaffold. Landscape never scrolls vertically:
 * footer is measured first, then artwork fills the remaining height. Metadata may
 * scroll horizontally. Portrait can scroll at large font scales, without shrinking
 * system font scale or touch targets. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CassettePlayerScreen(
    theme: WalkmanTheme, state: CassetteScreenState, actions: CassetteActions,
    modifier: Modifier = Modifier, visible: Boolean = true,
    mode: NowPlayingMode = NowPlayingMode.CASSETTE,
    albumArt: @Composable (Modifier) -> Unit = {},
) {
    if (mode == NowPlayingMode.CASSETTE) {
        CassetteDeckScreen(theme, state, actions, modifier, visible)
        return
    }
    CompositionLocalProvider(LocalContentColor provides theme.ink) {
        MaterialTheme(colorScheme = theme.colors) {
            var preview by remember(state.title, state.durationMs, state.seekEnabled, state.timelineKnown) { mutableStateOf<Float?>(null) }
            var deckSeeking by remember(state.title, state.durationMs, state.seekEnabled, state.timelineKnown) { mutableStateOf(false) }
            var artworkVisible by remember { mutableStateOf(false) }
            val known = state.durationMs > 0 && state.timelineKnown
            val canSeek = known && state.seekEnabled
            val position = if (!known) 0L else preview?.let { (state.durationMs * it).toLong() }
                ?: state.positionMs.coerceIn(0, state.durationMs)
            val progress = if (known) TransportGeometry.progress(position, state.durationMs) else 0f
            val attribution = listOf(NowPlayingText.sanitize(state.artist), NowPlayingText.sanitize(state.album))
                .filter(String::isNotBlank).joinToString(" · ")
            val red = if (theme.dark) Color(0xFFFF6969) else Color(0xFFB4232C)
            val artwork: @Composable (Modifier) -> Unit = { artModifier ->
                NowPlayingVisual(mode, theme, state.title, state.artist, position, state.durationMs,
                    state.playing, deckSeeking || preview != null, { deckSeeking = it }, actions.seek,
                    artModifier.testTag("cassette-art").onGloballyPositioned {
                        val bounds = it.boundsInWindow()
                        artworkVisible = bounds.width > 0f && bounds.height > 0f
                    }, visible = visible && artworkVisible, durationKnown = known, seekEnabled = canSeek,
                    albumArt = albumArt)
            }
            val seek: @Composable (Boolean) -> Unit = { inline ->
                ProgressAndTime(state.durationMs, position, progress, canSeek, red, theme, inline,
                    onChange = { preview = it }, onFinish = {
                        if (canSeek) preview?.let { actions.seek((state.durationMs * it).toLong()) }
                        preview = null
                    })
            }
            BoxWithConstraints(modifier.fillMaxSize().background(theme.body).testTag("cassette-player")) {
                if (maxWidth > maxHeight) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 8.dp).testTag("cassette-content"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(64.dp).fillMaxHeight().testTag("thumb-left"),
                            verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                            UtilityButton(R.drawable.ic_expand, "Thu nhỏ trình phát", actions.close)
                            FavoriteButton(state.favorite, actions.favorite)
                            UtilityButton(R.drawable.ic_queue, "Hàng đợi", actions.queue)
                            UtilityButton(R.drawable.ic_audio_controls, "Cài đặt âm thanh", actions.sound)
                            ShuffleButton(state, actions)
                        }
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                val ratio = if (mode == NowPlayingMode.VINYL) 1.15f else 1.6f
                                val width = minOf(maxWidth, maxHeight * ratio)
                                artwork(Modifier.width(width).height(width / ratio))
                            }
                            SignalStrip(theme,state,attribution)
                            seek(true)
                        }
                        Column(Modifier.width(64.dp).fillMaxHeight().testTag("thumb-right"),
                            verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                            UtilityButton(R.drawable.ic_palette, "Chọn màu trình phát", actions.palette)
                            PreviousButton(actions.previous)
                            PlayButton(state.requested, actions.toggle)
                            NextButton(actions.next)
                            RepeatButton(state, actions)
                        }
                    }
                } else {
                    val viewportHeight = maxHeight
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewportHeight)
                        .padding(horizontal = 12.dp, vertical = 8.dp).testTag("cassette-content"),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
                        Row(Modifier.fillMaxWidth().testTag("cassette-status"), verticalAlignment = Alignment.CenterVertically) {
                            UtilityButton(R.drawable.ic_expand, "Thu nhỏ trình phát", actions.close)
                            Box(Modifier.weight(1f)) { QueueIndex(state) }
                            ShuffleButton(state, actions); RepeatButton(state, actions)
                            FavoriteButton(state.favorite, actions.favorite)
                        }
                        // Keep artwork, metadata and seek together instead of distributing
                        // a large empty band between them when tags are absent.
                        Column(Modifier.fillMaxWidth(), horizontalAlignment=Alignment.CenterHorizontally) {
                        val vinylRatio=if(LocalDensity.current.fontScale>=1.5f)1.4f else 1.15f
                        artwork(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .aspectRatio(if (mode == NowPlayingMode.VINYL) vinylRatio else 1.5f))
                        if (attribution.isNotBlank() || state.specs.any(String::isNotBlank)) Column(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("cassette-metadata"),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (attribution.isNotBlank()) Text(attribution, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, color = theme.ink)
                            if (state.specs.any(String::isNotBlank)) {
                                FlowRow(Modifier.fillMaxWidth().testTag("cassette-specs"),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    state.specs.filter(String::isNotBlank).forEach { SpecChip(it, theme) }
                                }
                            }
                        }
                        seek(false)
                        }
                        Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            PreviousButton(actions.previous); PlayButton(state.requested, actions.toggle); NextButton(actions.next)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TextButton(onClick = actions.queue, contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp), modifier = Modifier.weight(1.25f).heightIn(min = 48.dp)
                                .semantics { contentDescription = "Hàng đợi" }) {
                                StudioIcon(R.drawable.ic_queue, null); Spacer(Modifier.width(6.dp)); Text("Hàng đợi", fontSize=14.sp,lineHeight=16.sp,maxLines=1)
                            }
                            TextButton(onClick = actions.sound, contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp), modifier = Modifier.weight(.75f).heightIn(min = 48.dp)
                                .semantics { contentDescription = "Cài đặt âm thanh" }) {
                                StudioIcon(R.drawable.ic_audio_controls, null); Spacer(Modifier.width(6.dp)); Text("EQ", fontSize=14.sp,lineHeight=16.sp,maxLines=1)
                            }
                            UtilityButton(R.drawable.ic_palette, "Chọn màu trình phát", actions.palette)
                        }
                    }
                }
                if (state.error != null) {
                    Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = theme.face, contentColor = theme.ink) {
                        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            StudioIcon(R.drawable.ic_error, null)
                            Text("Chưa đọc được bài hát", Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = actions.retry) { Text("Thử lại") }
                            TextButton(onClick = actions.details) { Text("Chi tiết") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProgressAndTime(duration: Long, position: Long, progress: Float, enabled: Boolean,
    red: Color, theme: WalkmanTheme, inline: Boolean, onChange: (Float) -> Unit, onFinish: () -> Unit) {
    val slider: @Composable (Modifier) -> Unit = { modifier ->
        Slider(value = progress, onValueChange = onChange, onValueChangeFinished = onFinish, enabled = enabled,
            modifier = modifier.heightIn(min = 48.dp).testTag("cassette-progress").semantics { contentDescription = "Tua bài hát" },
            thumb = { Box(Modifier.size(12.dp).background(if (enabled) red else theme.ink.copy(alpha = .35f), CircleShape)) },
            track = { Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                val y = size.height / 2
                drawLine(theme.ink.copy(alpha = .22f), Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                drawLine(red, Offset(0f, y), Offset(size.width * progress, y), size.height, StrokeCap.Round)
            } })
    }
    val elapsed: @Composable () -> Unit = {
        Text(clockTime(position), Modifier.testTag("cassette-time-position"), fontFamily = FontFamily.Monospace,
            fontSize = 13.sp, lineHeight = 16.sp, maxLines = 1, color = theme.ink)
    }
    val total: @Composable () -> Unit = {
        Text(if (duration > 0) clockTime(duration) else "--:--", Modifier.testTag("cassette-time-duration"),
            fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 1, color = theme.ink)
    }
    if (inline) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        elapsed(); slider(Modifier.weight(1f)); total()
    } else Column(Modifier.fillMaxWidth()) {
        slider(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { elapsed(); total() }
    }
}
/** The arrow makes the existing horizontal overflow explicit. The scrolling
 * viewport and decorative cue are separate so the cue never covers a source value. */
@Composable private fun SignalStrip(theme:WalkmanTheme,state:CassetteScreenState,attribution:String) {
    val scroll=rememberScrollState()
    Row(Modifier.fillMaxWidth().testTag("cassette-metadata"),verticalAlignment=Alignment.CenterVertically) {
        Row(Modifier.weight(1f).testTag("cassette-metadata-viewport").horizontalScroll(scroll),
            horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            QueueIndex(state)
            if(attribution.isNotBlank()) Text(attribution,fontSize=14.sp,lineHeight=18.sp,maxLines=1,color=theme.ink)
            state.specs.filter(String::isNotBlank).forEach { SpecChip(it,theme) }
        }
        Box(Modifier.width(24.dp),contentAlignment=Alignment.Center) {
            if(scroll.maxValue>0) Canvas(Modifier.size(16.dp)) {
                val middle=size.height/2
                drawLine(theme.ink.copy(alpha=.8f),Offset(size.width*.18f,middle),Offset(size.width*.82f,middle),2.dp.toPx(),StrokeCap.Round)
                drawLine(theme.ink.copy(alpha=.8f),Offset(size.width*.18f,middle),Offset(size.width*.38f,size.height*.28f),2.dp.toPx(),StrokeCap.Round)
                drawLine(theme.ink.copy(alpha=.8f),Offset(size.width*.82f,middle),Offset(size.width*.62f,size.height*.72f),2.dp.toPx(),StrokeCap.Round)
            }
        }
    }
}

@Composable private fun SpecChip(text: String, theme: WalkmanTheme) {
    Surface(color = theme.face, contentColor = theme.ink, shape = RoundedCornerShape(6.dp)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 13.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
@Composable private fun QueueIndex(state: CassetteScreenState) {
    val index = if (state.queueIndex > 0 && state.queueCount >= state.queueIndex)
        String.format(Locale.ROOT, "%03d/%03d", state.queueIndex, state.queueCount) else "—/—"
    Text(index, Modifier.testTag("cassette-queue-index").semantics { contentDescription = "Vị trí trong hàng đợi hiện tại: $index" },
        fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
@Composable private fun FavoriteButton(favorite: Boolean, action: () -> Unit) {
    IconToggleButton(checked = favorite, onCheckedChange = { action() }, modifier = Modifier.size(48.dp)) {
        StudioIcon(R.drawable.ic_favorite, if (favorite) "Bỏ yêu thích" else "Thêm vào yêu thích")
    }
}
@Composable private fun ShuffleButton(state: CassetteScreenState, actions: CassetteActions) {
    IconToggleButton(checked = state.shuffled, onCheckedChange = { actions.shuffle() }, modifier = Modifier.size(48.dp)) {
        StudioIcon(R.drawable.ic_shuffle, "Ngẫu nhiên: ${if (state.shuffled) "bật" else "tắt"}")
    }
}
@Composable private fun RepeatButton(state: CassetteScreenState, actions: CassetteActions) {
    IconToggleButton(checked = state.repeatOne, onCheckedChange = { actions.repeat() }, modifier = Modifier.size(48.dp)) {
        StudioIcon(R.drawable.ic_repeat, "Lặp một bài: ${if (state.repeatOne) "bật" else "tắt"}")
    }
}
@Composable private fun UtilityButton(icon: Int, label: String, action: () -> Unit) {
    IconButton(onClick = action, modifier = Modifier.size(48.dp)) { StudioIcon(icon, label) }
}
@Composable private fun PreviousButton(action: () -> Unit) {
    IconButton(onClick = action, modifier = Modifier.size(56.dp)) { StudioIcon(R.drawable.ic_previous, "Bài trước") }
}
@Composable private fun NextButton(action: () -> Unit) {
    IconButton(onClick = action, modifier = Modifier.size(56.dp)) { StudioIcon(R.drawable.ic_next, "Bài tiếp") }
}
@Composable private fun PlayButton(requested: Boolean, action: () -> Unit) {
    FilledIconButton(onClick = action, modifier = Modifier.size(64.dp)) {
        StudioIcon(if (requested) R.drawable.ic_pause else R.drawable.ic_play, if (requested) "Tạm dừng" else "Phát", Modifier.size(32.dp))
    }
}
