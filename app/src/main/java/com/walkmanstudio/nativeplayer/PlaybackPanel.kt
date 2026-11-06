// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlin.math.min

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
    Surface(tonalElevation = 3.dp) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClickLabel = "Mở Đang phát", onClick = open).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TrackCover(track, artwork.size(48.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
fun NowPlaying(model:PlaybackViewModel,artwork:Modifier,theme:WalkmanTheme,mode:NowPlayingMode,chooseTheme:(WalkmanTheme)->Unit,favorite:(TrackEntity)->Unit,close:()->Unit) {
    MaterialTheme(colorScheme=theme.colors) { NowPlayingContent(model,artwork,theme,mode,chooseTheme,favorite,close) }
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NowPlayingContent(model: PlaybackViewModel, artwork: Modifier,theme:WalkmanTheme,mode:NowPlayingMode,chooseTheme:(WalkmanTheme)->Unit,favorite:(TrackEntity)->Unit, close: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val track by model.currentTrack.collectAsStateWithLifecycle()
    val signal by model.signal.collectAsStateWithLifecycle()
    val dsp by model.dsp.collectAsStateWithLifecycle()
    var themeMenu by remember {mutableStateOf(false)}
    var sheet by remember { mutableStateOf(false) }
    var sheetMode by remember { mutableStateOf("sound") }
    var details by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    var seeking by remember(state.id) { mutableStateOf(false) }
    BackHandler(onBack = close)
    val paletteMenu: @Composable () -> Unit = {
        DropdownMenu(themeMenu, { themeMenu=false }) {
            WalkmanTheme.entries.forEach { item ->
                DropdownMenuItem(text={ Text(if(mode==NowPlayingMode.CLASSIC) "${item.model} · ${item.year}" else when(item) {
                    WalkmanTheme.TPS_L2->"Xanh";WalkmanTheme.DD9->"Đen";WalkmanTheme.RH1->"Bạc";WalkmanTheme.WM1Z->"Vàng"
                }) },onClick={chooseTheme(item);themeMenu=false})
            }
        }
    }
    Scaffold(contentWindowInsets=WindowInsets.safeDrawing, topBar = {
        if(mode==NowPlayingMode.CLASSIC) TopAppBar(
            title={Text("Đang phát",maxLines=1,overflow=TextOverflow.Ellipsis)},
            navigationIcon={IconButton(onClick=close){StudioIcon(R.drawable.ic_expand,"Thu nhỏ trình phát")}},
            actions={Box { IconButton(onClick={themeMenu=true}){StudioIcon(R.drawable.ic_palette,"Chọn theme Walkman")};paletteMenu() }})
    }) { padding ->
        if(mode!=NowPlayingMode.CLASSIC) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                CassettePlayerScreen(theme, CassetteScreenState(
                    title=state.title, artist=state.artist, album=state.album,
                    positionMs=state.positionMs, durationMs=state.durationMs, playing=state.playing,
                    requested=state.requested, playbackState=state.playbackState, queueIndex=state.queueIndex, queueCount=state.queueCount,
                    shuffled=state.shuffled, repeatOne=state.repeatOne, favorite=track?.takeIf { it.id.toString()==state.id }?.isFavorite==true,
                    specs=CassetteSignal.chips(signal,dsp.sourceDirect,!dsp.sourceDirect), error=state.error, seekEnabled=state.seekEnabled, timelineKnown=state.timelineKnown),
                    CassetteActions(toggle=model::toggle, previous=model::previous, next=model::next,
                        favorite={track?.takeIf { it.id.toString()==state.id }?.let(favorite)}, queue={sheetMode="queue";sheet=true},
                        sound={sheetMode="sound";sheet=true}, shuffle=model::toggleShuffle,
                        repeat=model::repeatOne, seek=model::seek, retry=model::retry, details={details=true},
                        close=close,palette={themeMenu=true}),
                    modifier=Modifier.fillMaxSize(), visible=!sheet&&!details&&!themeMenu&&!sleepMenu,mode=mode) { artModifier ->
                    TrackCover(track,artModifier,large=true)
                }
                Box(Modifier.align(Alignment.TopEnd)) { paletteMenu() }
            }
        } else BoxWithConstraints(Modifier.fillMaxSize().padding(padding).background(Brush.linearGradient(listOf(theme.body,theme.face)))) {
            val wide = maxWidth > 600.dp
            val coverSize = if (wide) minOf(maxHeight - 48.dp, 360.dp).coerceAtLeast(120.dp) else minOf(maxWidth - 64.dp, maxHeight * 0.46f, 360.dp).coerceAtLeast(120.dp)
            val visualWidth = if (mode == NowPlayingMode.CASSETTE) {
                minOf(if (wide) (maxWidth - 64.dp) / 2 else maxWidth - 48.dp, 360.dp).coerceAtLeast(120.dp)
            } else coverSize
            val cover: @Composable () -> Unit = {
                Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    if (mode == NowPlayingMode.CLASSIC) {
                        TrackCover(track, artwork.size(coverSize).clip(RoundedCornerShape(16.dp)).border(2.dp,theme.accent.copy(alpha=.5f),RoundedCornerShape(16.dp)), large = true)
                    } else {
                        key(state.id, mode) {
                            NowPlayingVisual(mode, theme, state.title, state.artist, state.positionMs, state.durationMs,
                                state.playing, seeking, { seeking = it }, model::seek,
                                Modifier.width(visualWidth).height(if (mode == NowPlayingMode.CASSETTE) visualWidth * .68f else visualWidth),
                                visible = !sheet && !details && !themeMenu && !sleepMenu) { artModifier ->
                                TrackCover(track, artModifier, large = true)
                            }
                        }
                    }
                    Column(Modifier.width(visualWidth).clip(RoundedCornerShape(6.dp)).background(theme.display).padding(horizontal=12.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text("${theme.model} · ${theme.year}",fontSize=14.sp,color=theme.displayInk,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text("WALKMAN STUDIO",Modifier.weight(1f),fontSize=12.sp,color=theme.displayInk,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(if(model.dsp.collectAsStateWithLifecycle().value.sourceDirect) "DIRECT" else "DSP",fontSize=12.sp,color=theme.displayInk)
                        }
                    }
                }
            }
            val controls: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if(state.artist.isNotBlank()) Text(state.artist, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.album.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    var dragging by remember(state.id) { mutableStateOf<Float?>(null) }
                    Slider(value = dragging ?: if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f,
                        onValueChange = { dragging = it; seeking = true }, enabled = state.seekEnabled,
                        onValueChangeFinished = { dragging?.let { model.seek((state.durationMs * it).toLong()) }; dragging = null; seeking = false },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Tua bài hát" })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(clockTime(state.positionMs), fontSize = 13.sp); Text(if(state.durationMs>0) clockTime(state.durationMs) else "--:--", fontSize = 13.sp) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        IconButton(onClick = model::previous, modifier = Modifier.size(56.dp)) { StudioIcon(R.drawable.ic_previous, "Bài trước") }
                        FilledIconButton(onClick = model::toggle, modifier = Modifier.size(64.dp)) { StudioIcon(if (state.requested) R.drawable.ic_pause else R.drawable.ic_play, if (state.requested) "Tạm dừng" else "Phát", Modifier.size(32.dp)) }
                        IconButton(onClick = model::next, modifier = Modifier.size(56.dp)) { StudioIcon(R.drawable.ic_next, "Bài tiếp") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        IconToggleButton(checked = state.repeatOne, onCheckedChange = { model.repeatOne() }) { StudioIcon(R.drawable.ic_repeat, if (state.repeatOne) "Tắt lặp một bài" else "Lặp một bài") }
                        Box {
                            TextButton(onClick = { sleepMenu = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.sleepRemainingMs > 0) "Hẹn giờ ${clockTime(state.sleepRemainingMs)}" else "Hẹn giờ") }
                            DropdownMenu(sleepMenu, { sleepMenu = false }) {
                                listOf(0, 15, 30, 60, 90).forEach { minutes -> DropdownMenuItem(text = { Text(if (minutes == 0) "Tắt hẹn giờ" else "$minutes phút") }, onClick = { model.sleep(minutes); sleepMenu = false }) }
                            }
                        }
                    }
                    if (state.error != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) { StudioIcon(R.drawable.ic_error, null); Text("Chưa đọc được bài hát", color = MaterialTheme.colorScheme.error) }
                        Row { TextButton(onClick = model::retry) { Text("Thử lại") }; TextButton(onClick = { details = true }) { Text("Chi tiết") } }
                    }
                    OutlinedButton(onClick = { sheetMode="sound"; sheet = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Âm thanh & thiết bị") }
                }
            }
            if (wide) Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { cover() }
                Box(Modifier.weight(1f)) { controls() }
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) { cover(); controls() }
        }
    }
    if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false }) {
        val queue by model.queue.collectAsStateWithLifecycle()
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 16.dp)) {
            item { Text(if(sheetMode=="queue") "Hàng đợi" else "Âm thanh & thiết bị", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(12.dp)) }
            if(sheetMode=="sound") {
                item { SystemVolumeButton() }
                item { SignalAndDsp(model) }
                item { Text("Hẹn giờ ngủ",style=MaterialTheme.typography.titleMedium); FlowRow { listOf(0,15,30,60,90).forEach { minutes -> TextButton(onClick={model.sleep(minutes)}) {Text(if(minutes==0) "Tắt" else "$minutes′")} } } }
            }
            if(sheetMode=="sound") item { Text("Hàng đợi", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 16.dp)) }
            itemsIndexed(queue, key = { i, item -> "$i-${item.mediaId}" }) { index, item ->
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
        text = { Text(state.title + "\n" + state.detail.orEmpty() + "\nLỗi đọc chưa đủ để kết luận tệp bị hỏng. Dùng Cài đặt → Xuất log để kiểm tra.") },
        confirmButton = { TextButton(onClick = { details = false }) { Text("Đóng") } })
}

