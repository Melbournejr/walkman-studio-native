// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.compose.ui.platform.testTag
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.walkmanstudio.nativeplayer.library.TrackEntity
import com.walkmanstudio.nativeplayer.library.LibraryDatabase
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.flowOf

@Composable
fun StudioIcon(@DrawableRes icon: Int, description: String? = null, modifier: Modifier = Modifier) {
    Icon(painterResource(icon), description, modifier)
}
fun clockTime(ms: Long): String = "${ms.coerceAtLeast(0) / 60000}:${(ms.coerceAtLeast(0) / 1000 % 60).toString().padStart(2, '0')}"

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlaybackPanel(model: PlaybackViewModel, artwork: Modifier = Modifier, open: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val track by model.currentTrack.collectAsStateWithLifecycle()
    MioMiniPlayer(state, open, model::toggle, model::next) { TrackCover(track, artwork.then(it)) }
}

@Composable
internal fun MioMiniPlayer(state: PlaybackUi, open: () -> Unit, toggle: () -> Unit,
    next: () -> Unit, cover: @Composable (Modifier) -> Unit) {
    if (state.id == null) return
    Surface(Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("mio-mini-player"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .48f))) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClickLabel = "Mở Đang phát", onClick = open).padding(start = 18.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                cover(Modifier.size(44.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.title, style = MaterialTheme.typography.titleMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.artist.isNotBlank()) Text(state.artist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.error != null) StudioIcon(R.drawable.ic_error, "Chưa đọc được bài hát", Modifier.size(20.dp))
                IconButton(onClick = toggle) { StudioIcon(if (state.requested) R.drawable.ic_pause else R.drawable.ic_play, if (state.requested) "Tạm dừng" else "Phát") }
                IconButton(onClick = next) { StudioIcon(R.drawable.ic_next, "Bài tiếp") }
            }
            LinearProgressIndicator(progress = { if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(1.5.dp))
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlaying(model:PlaybackViewModel,artwork:Modifier,theme:WalkmanTheme,mode:NowPlayingMode,chooseTheme:(WalkmanTheme)->Unit,chooseMode:(NowPlayingMode)->Unit,favorite:(TrackEntity)->Unit,close:()->Unit) {
    val colors = if (mode == NowPlayingMode.CLASSIC) MaterialTheme.colorScheme else theme.colors
    MaterialTheme(colorScheme = colors) { NowPlayingContent(model, artwork, theme, mode, chooseTheme, chooseMode, favorite, close) }
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NowPlayingContent(model: PlaybackViewModel, artwork: Modifier,theme:WalkmanTheme,mode:NowPlayingMode,chooseTheme:(WalkmanTheme)->Unit,chooseMode:(NowPlayingMode)->Unit,favorite:(TrackEntity)->Unit, close: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val track by model.currentTrack.collectAsStateWithLifecycle()
    val signal by model.signal.collectAsStateWithLifecycle()
    val dsp by model.dsp.collectAsStateWithLifecycle()
    var displayMenu by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var sheetMode by remember { mutableStateOf("sound") }
    var details by remember { mutableStateOf(false) }
    val current = track?.takeIf { it.id.toString() == state.id }
    BackHandler(onBack = close)
    val actions = CassetteActions(
        toggle = model::toggle, previous = model::previous, next = model::next,
        favorite = { current?.let(favorite) }, queue = { sheetMode = "queue"; sheet = true },
        sound = { sheetMode = "sound"; sheet = true }, shuffle = model::toggleShuffle,
        repeat = model::repeatOne, seek = model::seek, retry = model::retry, details = { details = true },
        close = close, palette = { displayMenu = true }, stop = model::stopDeck,
    )
    val displayOptions: @Composable () -> Unit = {
        DropdownMenu(displayMenu, { displayMenu = false }) {
            if (mode == NowPlayingMode.CLASSIC) {
                DropdownMenuItem(text = { Text("Hàng đợi") }, onClick = { displayMenu = false; actions.queue() })
                DropdownMenuItem(text = { Text("Audio Lab") }, onClick = { displayMenu = false; actions.sound() })
                HorizontalDivider()
            }
            NowPlayingMode.entries.forEach { choice ->
                DropdownMenuItem(text = { Text(choice.label) }, onClick = { chooseMode(choice); displayMenu = false })
            }
            if (mode != NowPlayingMode.CLASSIC) {
                HorizontalDivider()
                WalkmanTheme.entries.forEach { choice ->
                    DropdownMenuItem(text = { Text(choice.model) }, onClick = { chooseTheme(choice); displayMenu = false })
                }
            }
        }
    }
    val modern = mode == NowPlayingMode.CLASSIC
    Scaffold(modifier = if (modern) Modifier.mioGraphite() else Modifier,
        containerColor = if (modern) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing, topBar = {
        if (mode == NowPlayingMode.CLASSIC) MioNowHeader(close, current?.isFavorite == true, current != null, actions.favorite) {
            Box {
                IconButton(onClick = { displayMenu = true }) { StudioIcon(R.drawable.ic_more, "Tùy chọn Đang phát") }
                displayOptions()
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            val specs = CassetteSignal.chips(signal, dsp.sourceDirect, !dsp.sourceDirect)
            if (mode == NowPlayingMode.CLASSIC) {
                MioNowPlaying(state, specs, actions,
                    highResolutionSource = CassetteSignal.isHighResolutionSource(signal)) {
                    modifier -> TrackCover(track, artwork.then(modifier), large = true)
                }
            } else {
                CassettePlayerScreen(theme, CassetteScreenState(
                    title = state.title, artist = state.artist, album = state.album,
                    positionMs = state.positionMs, durationMs = state.durationMs, playing = state.playing,
                    requested = state.requested, playbackState = state.playbackState, queueIndex = state.queueIndex,
                    queueCount = state.queueCount, shuffled = state.shuffled, repeatOne = state.repeatOne,
                    favorite = current?.isFavorite == true, specs = specs, error = state.error,
                    seekEnabled = state.seekEnabled, timelineKnown = state.timelineKnown,
                ), actions, visible = !sheet && !details && !displayMenu, mode = mode) { modifier ->
                    TrackCover(track, modifier, large = true)
                }
                Box(Modifier.align(Alignment.TopEnd)) { displayOptions() }
            }
        }
    }
    if (sheet && sheetMode == "queue") {
        BackHandler { sheet = false }
        val queue by model.queue.collectAsStateWithLifecycle()
        val database = LibraryDatabase.getInstance(LocalContext.current.applicationContext)
        val rows = remember(queue) { queue.map { item ->
            MioQueueItem(item.mediaId, NowPlayingText.display(mediaTitle = item.mediaMetadata.title?.toString()).title,
                NowPlayingText.sanitize(item.mediaMetadata.artist?.toString()))
        } }
        Surface(Modifier.fillMaxSize()) {
            MioQueue(rows, state.queueIndex - 1, { sheet = false }, model::queuePlay, model::remove) { item, modifier ->
                val source = remember(database, item.id) {
                    item.id.toLongOrNull()?.let(database.libraryDao()::observeTrack) ?: flowOf(null)
                }
                val queuedTrack by source.collectAsStateWithLifecycle(initialValue = null)
                TrackCover(queuedTrack, modifier)
            }
        }
    }
    if (sheet && sheetMode == "sound") ModalBottomSheet(onDismissRequest = { sheet = false }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 16.dp)) {
            item { Text("Audio Lab", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(12.dp)) }
            item { SystemVolumeButton() }
            item { AudioLab(model) }
            item { Text("Hẹn giờ ngủ",style=MaterialTheme.typography.titleMedium); FlowRow { listOf(0,15,30,60,90).forEach { minutes -> TextButton(onClick={model.sleep(minutes)}) {Text(if(minutes==0) "Tắt" else "$minutes′")} } } }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("Chi tiết lỗi đọc") },
        text = { Text(state.title + "\n" + state.detail.orEmpty() + "\nLỗi đọc chưa đủ để kết luận tệp bị hỏng. Dùng Cài đặt → Giới thiệu → Xuất log để kiểm tra.") },
        confirmButton = { TextButton(onClick = { details = false }) { Text("Đóng") } })
}

