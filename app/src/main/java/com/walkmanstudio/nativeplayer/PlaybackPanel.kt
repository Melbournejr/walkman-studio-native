// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
    if (state.id == null) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClickLabel = "Mở Đang phát", onClick = open).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TrackCover(track, artwork.size(48.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.artist.isNotBlank()) Text(state.artist, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.error != null) StudioIcon(R.drawable.ic_error, "Chưa đọc được bài hát", Modifier.size(20.dp))
                IconButton(onClick = model::toggle) { StudioIcon(if (state.requested) R.drawable.ic_pause else R.drawable.ic_play, if (state.requested) "Tạm dừng" else "Phát") }
                IconButton(onClick = model::next) { StudioIcon(R.drawable.ic_next, "Bài tiếp") }
            }
            LinearProgressIndicator(progress = { if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(2.dp))
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
    var sleepMenu by remember { mutableStateOf(false) }
    val current = track?.takeIf { it.id.toString() == state.id }
    BackHandler(onBack = close)
    val actions = CassetteActions(
        toggle = model::toggle, previous = model::previous, next = model::next,
        favorite = { current?.let(favorite) }, queue = { sheetMode = "queue"; sheet = true },
        sound = { sheetMode = "sound"; sheet = true }, shuffle = model::toggleShuffle,
        repeat = model::repeatOne, seek = model::seek, retry = model::retry, details = { details = true },
        close = close, palette = { displayMenu = true },
    )
    val displayOptions: @Composable () -> Unit = {
        DropdownMenu(displayMenu, { displayMenu = false }) {
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
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, topBar = {
        if (mode == NowPlayingMode.CLASSIC) TopAppBar(
            title = { Text("Đang phát") },
            navigationIcon = { IconButton(onClick = close) { StudioIcon(R.drawable.ic_expand, "Thu nhỏ trình phát") } },
            actions = {
                IconToggleButton(current?.isFavorite == true, { actions.favorite() }, enabled = current != null) {
                    StudioIcon(R.drawable.ic_favorite, "Yêu thích bài hát")
                }
                Box {
                    IconButton(onClick = { displayMenu = true }) { StudioIcon(R.drawable.ic_palette, "Chế độ hiển thị") }
                    displayOptions()
                }
            },
        )
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val specs = CassetteSignal.chips(signal, dsp.sourceDirect, !dsp.sourceDirect)
            if (mode == NowPlayingMode.CLASSIC) {
                MioNowPlaying(state, specs, actions, volume = { SystemVolumeButton(Modifier.heightIn(min = MioSpacing.touch)) },
                    sleep = {
                        Box {
                            TextButton(onClick = { sleepMenu = true }) {
                                Text(if (state.sleepRemainingMs > 0) "Hẹn giờ ${clockTime(state.sleepRemainingMs)}" else "Hẹn giờ")
                            }
                            DropdownMenu(sleepMenu, { sleepMenu = false }) {
                                listOf(0, 15, 30, 60, 90).forEach { minutes ->
                                    DropdownMenuItem(text = { Text(if (minutes == 0) "Tắt hẹn giờ" else "$minutes phút") },
                                        onClick = { model.sleep(minutes); sleepMenu = false })
                                }
                            }
                        }
                    }) { modifier -> TrackCover(track, artwork.then(modifier), large = true) }
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
    if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false }) {
        val queue by model.queue.collectAsStateWithLifecycle()
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 16.dp)) {
            item { Text(if(sheetMode=="queue") "Hàng đợi" else "Audio Lab", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(12.dp)) }
            if(sheetMode=="sound") {
                item { SystemVolumeButton() }
                item { AudioLab(model) }
                item { Text("Hẹn giờ ngủ",style=MaterialTheme.typography.titleMedium); FlowRow { listOf(0,15,30,60,90).forEach { minutes -> TextButton(onClick={model.sleep(minutes)}) {Text(if(minutes==0) "Tắt" else "$minutes′")} } } }
            }
            if (sheetMode == "queue" && queue.isEmpty()) item { Text("Hàng đợi đang trống.", Modifier.padding(vertical = 16.dp)) }
            if (sheetMode == "queue") itemsIndexed(queue, key = { i, item -> "$i-${item.mediaId}" }) { index, item ->
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { model.queuePlay(index) }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(NowPlayingText.display(mediaTitle=item.mediaMetadata.title?.toString()).title, fontSize = 16.sp, color = if (item.mediaId == state.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 2)
                        if(NowPlayingText.sanitize(item.mediaMetadata.artist?.toString()).isNotBlank()) Text(NowPlayingText.sanitize(item.mediaMetadata.artist?.toString()), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { model.remove(index) }) { StudioIcon(R.drawable.ic_close, "Bỏ ${item.mediaMetadata.title} khỏi hàng đợi") }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("Chi tiết lỗi đọc") },
        text = { Text(state.title + "\n" + state.detail.orEmpty() + "\nLỗi đọc chưa đủ để kết luận tệp bị hỏng. Dùng Cài đặt → Giới thiệu → Xuất log để kiểm tra.") },
        confirmButton = { TextButton(onClick = { details = false }) { Text("Đóng") } })
}

