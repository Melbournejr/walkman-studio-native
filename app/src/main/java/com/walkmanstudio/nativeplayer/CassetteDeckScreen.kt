// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import java.util.Locale

/** Keep the service's exact integer clock except while displaying an explicit seek preview. */
internal object CassetteDeckPosition {
    fun display(positionMs: Long, durationMs: Long, timelineKnown: Boolean, previewProgress: Float? = null): Long {
        if (!timelineKnown || durationMs <= 0L) return 0L
        return if (previewProgress == null) positionMs.coerceIn(0L, durationMs)
        else (previewProgress.coerceIn(0f, 1f).toDouble() * durationMs.toDouble()).toLong().coerceIn(0L, durationMs)
    }
}

/** Portrait cassette dimensions; landscape has a separate full-width vector drawing. */
internal object CassetteDeckGeometry {
    const val TAPE_RATIO = 1.57f
    const val HORIZONTAL_INSET_DP = 12f
    const val VERTICAL_INSET_DP = 12f
    const val TAPE_KEY_GAP_DP = 6f
    const val KEY_BAND_DP = 72f
}

/** Cassette-only presentation. No player is created here and every transport action
 * is delegated to the existing controller callbacks. Vinyl keeps its previous shell.
 */
@Composable
internal fun CassetteDeckScreen(
    theme: WalkmanTheme,
    state: CassetteScreenState,
    actions: CassetteActions,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    CompositionLocalProvider(LocalContentColor provides theme.ink) {
        MaterialTheme(colorScheme = theme.colors) {
            var preview by remember(state.title, state.durationMs, state.timelineKnown, state.seekEnabled) {
                mutableStateOf<Float?>(null)
            }
            var dragging by remember(state.title, state.durationMs, state.timelineKnown, state.seekEnabled) {
                mutableStateOf<Float?>(null)
            }
            val known = state.timelineKnown && state.durationMs > 0
            val canSeek = known && state.seekEnabled
            val sourceProgress = if (known) TransportGeometry.progress(state.positionMs, state.durationMs) else 0f
            val progress = if (known) dragging ?: preview ?: sourceProgress else 0f
            val position = CassetteDeckPosition.display(state.positionMs, state.durationMs, known, dragging ?: preview)
            val attribution = listOf(NowPlayingText.sanitize(state.artist), NowPlayingText.sanitize(state.album))
                .filter(String::isNotBlank).joinToString(" · ")
            val animations = transportAnimationAllowed(true, false) && visible
            val rotation = cassetteDeckRotation(state.playing, dragging != null || preview != null, visible)
            val latestProgress by rememberUpdatedState(sourceProgress)
            val latestSeek by rememberUpdatedState(actions.seek)
            val deckSeek = Modifier.semantics {
                // Do not merge descendants: physical keys remain individual TalkBack buttons.
                contentDescription = "Cassette. Vuốt ngang để tua bài hát"
                stateDescription = "${clockTime(position)} trên ${if (state.durationMs > 0) clockTime(state.durationMs) else "--:--"}, ${if (state.playing) "đang phát" else "tạm dừng"}"
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                if (canSeek) {
                    setProgress { value -> latestSeek((value.coerceIn(0f, 1f) * state.durationMs).toLong()); true }
                    customActions = listOf(
                        CustomAccessibilityAction("Lùi 10 giây") { latestSeek((position - 10_000L).coerceAtLeast(0)); true },
                        CustomAccessibilityAction("Tiến 10 giây") { latestSeek((position + 10_000L).coerceAtMost(state.durationMs)); true },
                    )
                }
            }.pointerInput(state.title, state.durationMs, canSeek) {
                if (!canSeek) return@pointerInput
                var start = 0f
                var delta = 0f
                detectHorizontalDragGestures(
                    onDragStart = { start = latestProgress; delta = 0f; dragging = start },
                    onDragCancel = { dragging = null },
                    onDragEnd = {
                        dragging?.let { latestSeek((it * state.durationMs).toLong()) }
                        dragging = null
                    },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        delta += amount
                        dragging = TransportGeometry.draggedProgress(start, delta, size.width.toFloat())
                    },
                )
            }
            val density = LocalDensity.current
            val queueHeaderHeight = with(density) { 14.sp.toDp() }
            val deck: @Composable (Modifier, Boolean) -> Unit = { deckModifier, queueInside ->
                // Tags refer to the real case/tape, not to unused space in a parent slot.
                Box(deckModifier.testTag("cassette-art").then(deckSeek)) {
                    CassetteDeckHousing(theme, Modifier.fillMaxSize().testTag("cassette-deck")) {
                        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp)) {
                            val tapeWidth = maxWidth
                            val tapeHeight = tapeWidth / CassetteDeckGeometry.TAPE_RATIO
                            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                                if (queueInside) Box(Modifier.fillMaxWidth().height(queueHeaderHeight)) {
                                    DeckQueueIndex(theme, state)
                                }
                                Box(Modifier.size(tapeWidth, tapeHeight).testTag("cassette-tape")) {
                                    CassetteFace(theme, state.title, state.artist, progress, position, rotation, known)
                                }
                                Spacer(Modifier.height(6.dp))
                                MechanicalTransport(theme, state.requested, state.playbackState, animations,
                                    actions.toggle, actions.previous, actions.next,
                                    Modifier.fillMaxWidth().height(72.dp))
                            }
                        }
                    }
                }
            }
            val seek: @Composable (Boolean) -> Unit = { inline ->
                DeckProgress(theme, state.durationMs, position, progress, canSeek, inline,
                    onChange = { preview = it }, onFinish = {
                        if (canSeek) preview?.let { actions.seek((it * state.durationMs).toLong()) }
                        preview = null
                    })
            }
            BoxWithConstraints(modifier.fillMaxSize().background(theme.body).testTag("cassette-player")) {
                val viewportHeight = maxHeight
                if (maxWidth > maxHeight) {
                    // Landscape is a full-width instrument panel. Its tape artwork is
                    // redrawn for this viewport; the portrait cassette is never stretched.
                    Box(Modifier.fillMaxSize().testTag("cassette-art").then(deckSeek)) {
                        CassetteDeckHousing(theme, Modifier.fillMaxSize().testTag("cassette-deck")) {
                            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag("cassette-content"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.fillMaxWidth().weight(1f).testTag("cassette-tape")) {
                                    LandscapeCassetteFace(theme, progress, position, rotation, known)
                                }
                                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f).testTag("cassette-metadata"),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                                WordFitLabel(state.title.ifBlank { "Chưa chọn bài" }, theme.ink,
                                                    Modifier.fillMaxSize(), "cassette-label", minFontSp = 12, maxFontSp = 20)
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Box(Modifier.width(IntrinsicSize.Max)) { DeckQueueIndex(theme, state) }
                                        }
                                        LandscapeMetadataStrip(theme, attribution, state.specs)
                                    }
                                    MechanicalTransport(theme, state.requested, state.playbackState, animations,
                                        actions.toggle, actions.previous, actions.next, Modifier.width(208.dp).height(64.dp))
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(Modifier.weight(1f)) { seek(true) }
                                    DeckAuxiliaryBar(theme, state, actions, Modifier.width(336.dp))
                                }
                            }
                        }
                    }
                } else {
                    // Keep an accessibility scroll ancestor, while measuring a bounded
                    // content group. At 100% the tested400x800/360x640 viewports do not scroll.
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        NaturalPortraitDeckLayout(
                            Modifier.fillMaxWidth().height(viewportHeight).padding(horizontal = 12.dp, vertical = 4.dp)
                                .testTag("cassette-content"),
                            queueHeaderHeight = queueHeaderHeight,
                            top = { DeckAuxiliaryBar(theme, state, actions, Modifier.fillMaxWidth(), portraitTop = true) },
                            bottom = { DeckAuxiliaryBar(theme, state, actions, Modifier.fillMaxWidth(), portraitBottom = true) },
                            information = {
                                Column(Modifier.fillMaxWidth().testTag("cassette-metadata")) {
                                    DeckMetadata(theme, attribution, state.specs, Modifier.padding(top = 4.dp),
                                        flowSpecs = density.fontScale < 1.5f)
                                    seek(false)
                                }
                            },
                            deck = { caseModifier -> deck(caseModifier, true) },
                        )
                    }
                }
                state.error?.let {
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

/** Measure real metadata first, then fit the cassette case to the space left by
 * real controls. Extra background is kept outside the compact deck/info group. */
@Composable
private fun NaturalPortraitDeckLayout(
    modifier: Modifier,
    queueHeaderHeight: Dp,
    top: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
    information: @Composable () -> Unit,
    deck: @Composable (Modifier) -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val childConstraints = Constraints(minWidth = width, maxWidth = width, maxHeight = height)
        val topPlaceable = subcompose("top", top).single().measure(childConstraints)
        val bottomPlaceable = subcompose("bottom", bottom).single().measure(childConstraints)
        val informationPlaceable = subcompose("information", information).single().measure(childConstraints)
        val overhead = (queueHeaderHeight + 24.dp + 6.dp + 72.dp).roundToPx()
        val horizontalInset = 24.dp.roundToPx()
        val availableCaseHeight = (height - topPlaceable.height - bottomPlaceable.height - informationPlaceable.height).coerceAtLeast(0)
        val tapeWidth = minOf((width - horizontalInset).coerceAtLeast(0).toFloat(),
            (availableCaseHeight - overhead).coerceAtLeast(0) * CassetteDeckGeometry.TAPE_RATIO)
        val caseWidth = (tapeWidth + horizontalInset).roundToInt().coerceAtMost(width)
        val caseHeight = (tapeWidth / CassetteDeckGeometry.TAPE_RATIO + overhead).roundToInt()
        val casePlaceable = subcompose("deck") {
            deck(Modifier.size(caseWidth.toDp(), caseHeight.toDp()))
        }.single().measure(Constraints.fixed(caseWidth, caseHeight))
        val groupHeight = topPlaceable.height + casePlaceable.height + informationPlaceable.height + bottomPlaceable.height
        // All controls belong to one compact device group. Extra background from
        // the physical tape ratio stays outside, never between device and controls.
        val groupTop = (height - groupHeight).coerceAtLeast(0) / 2
        layout(width, height) {
            topPlaceable.placeRelative(0, groupTop)
            val caseTop = groupTop + topPlaceable.height
            casePlaceable.placeRelative((width - casePlaceable.width) / 2, caseTop)
            informationPlaceable.placeRelative(0, caseTop + casePlaceable.height)
            bottomPlaceable.placeRelative(0, caseTop + casePlaceable.height + informationPlaceable.height)
        }
    }
}

@Composable
private fun DeckAuxiliaryBar(
    theme: WalkmanTheme, state: CassetteScreenState, actions: CassetteActions, modifier: Modifier,
    queueInBar: Boolean = false, portraitTop: Boolean = false, portraitBottom: Boolean = false,
) {
    Row(modifier.height(48.dp).testTag(if (portraitBottom) "cassette-auxiliary-bottom" else "cassette-status"),
        horizontalArrangement = if (portraitTop || portraitBottom) Arrangement.SpaceEvenly else Arrangement.End,
        verticalAlignment = Alignment.CenterVertically) {
        if (queueInBar) Box(Modifier.weight(1f)) { DeckQueueIndex(theme, state) }
        if (!portraitBottom) {
            IconButton(onClick = actions.close, modifier = Modifier.size(48.dp)) {
                StudioIcon(R.drawable.ic_expand, "Thu nhỏ trình phát")
            }
            IconToggleButton(checked = state.favorite, onCheckedChange = { actions.favorite() }, modifier = Modifier.size(48.dp)) {
                StudioIcon(R.drawable.ic_favorite, if (state.favorite) "Bỏ yêu thích" else "Thêm vào yêu thích")
            }
            IconToggleButton(checked = state.shuffled, onCheckedChange = { actions.shuffle() }, modifier = Modifier.size(48.dp)) {
                StudioIcon(R.drawable.ic_shuffle, "Ngẫu nhiên: ${if (state.shuffled) "bật" else "tắt"}")
            }
            IconToggleButton(checked = state.repeatOne, onCheckedChange = { actions.repeat() }, modifier = Modifier.size(48.dp)) {
                StudioIcon(R.drawable.ic_repeat, "Lặp một bài: ${if (state.repeatOne) "bật" else "tắt"}")
            }
        }
        if (!portraitTop) {
            IconButton(onClick = actions.queue, modifier = Modifier.size(48.dp)) { StudioIcon(R.drawable.ic_queue, "Hàng đợi") }
            IconButton(onClick = actions.sound, modifier = Modifier.size(48.dp)) { StudioIcon(R.drawable.ic_audio_controls, "Cài đặt âm thanh") }
            IconButton(onClick = actions.palette, modifier = Modifier.size(48.dp)) { StudioIcon(R.drawable.ic_palette, "Chọn màu trình phát") }
        }
    }
}

@Composable
private fun DeckQueueIndex(theme: WalkmanTheme, state: CassetteScreenState) {
    val index = if (state.queueIndex > 0 && state.queueCount >= state.queueIndex)
        String.format(Locale.ROOT, "%03d/%03d", state.queueIndex, state.queueCount) else "—/—"
    Text(index, Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("cassette-queue-index")
        .semantics { contentDescription = "Vị trí trong hàng đợi hiện tại: $index" },
        color = theme.ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeckMetadata(theme: WalkmanTheme, attribution: String, specs: List<String>, modifier: Modifier = Modifier, flowSpecs: Boolean) {
    val validSpecs = specs.filter(String::isNotBlank)
    if (attribution.isBlank() && validSpecs.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (attribution.isNotBlank()) Text(attribution,
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            color = theme.ink, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 1, softWrap = false)
        if (validSpecs.isNotEmpty()) BoxWithConstraints(Modifier.fillMaxWidth()) {
            val chipWidth: Dp = maxWidth
            val chip: @Composable (String) -> Unit = { value ->
                Surface(shape = RoundedCornerShape(6.dp), color = theme.face, contentColor = theme.ink) {
                    Text(value, Modifier.widthIn(max = chipWidth).padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 13.sp, lineHeight = 16.sp)
                }
            }
            if (flowSpecs) {
                FlowRow(Modifier.fillMaxWidth().testTag("cassette-metadata-viewport"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    validSpecs.forEach { chip(it) }
                }
            } else {
                Row(Modifier.fillMaxWidth().testTag("cassette-metadata-viewport").horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Wrap each complete chip within the viewport; no truncation of source data.
                    validSpecs.forEach { chip(it) }
                }
            }
        }
    }
}

/** One horizontal line of real metadata below the landscape tape. Each technical
 * value remains fully reachable by scrolling, including at large font scales. */
@Composable
private fun LandscapeMetadataStrip(theme: WalkmanTheme, attribution: String, specs: List<String>) {
    val values = specs.filter(String::isNotBlank)
    if (attribution.isBlank() && values.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val chipWidth = maxWidth
        Row(Modifier.fillMaxWidth().testTag("cassette-metadata-viewport").horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (attribution.isNotBlank()) Text(attribution, color = theme.ink,
                fontSize = 13.sp, lineHeight = 16.sp, maxLines = 1, softWrap = false)
            values.forEach { value ->
                Surface(shape = RoundedCornerShape(6.dp), color = theme.face, contentColor = theme.ink) {
                    Text(value, Modifier.widthIn(max = chipWidth).padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 13.sp, lineHeight = 16.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeckProgress(
    theme: WalkmanTheme, duration: Long, position: Long, progress: Float, enabled: Boolean,
    inline: Boolean, onChange: (Float) -> Unit, onFinish: () -> Unit,
) {
    val red = if (theme.dark) Color(0xFFFF6969) else Color(0xFFB4232C)
    val slider: @Composable (Modifier) -> Unit = { modifier ->
        Slider(value = progress, onValueChange = onChange, onValueChangeFinished = onFinish, enabled = enabled,
            modifier = modifier.heightIn(min = 48.dp).testTag("cassette-progress").semantics { contentDescription = "Tua bài hát" },
            thumb = { Box(Modifier.size(12.dp).background(if (enabled) red else theme.ink.copy(alpha = .35f), CircleShape)) },
            track = { Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                val y = size.height / 2f
                drawLine(theme.ink.copy(alpha = .22f), Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                drawLine(red, Offset(0f, y), Offset(size.width * progress, y), size.height, StrokeCap.Round)
            } })
    }
    val elapsed: @Composable () -> Unit = {
        Text(clockTime(position), Modifier.testTag("cassette-time-position"), color = theme.ink,
            fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 1)
    }
    val total: @Composable () -> Unit = {
        Text(if (duration > 0) clockTime(duration) else "--:--", Modifier.testTag("cassette-time-duration"), color = theme.ink,
            fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 1)
    }
    // A wide slider remains a real 48dp target; time labels are separate from it.
    if (inline) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        elapsed(); slider(Modifier.weight(1f)); total()
    } else Column(Modifier.fillMaxWidth()) {
        slider(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { elapsed(); total() }
    }
}
