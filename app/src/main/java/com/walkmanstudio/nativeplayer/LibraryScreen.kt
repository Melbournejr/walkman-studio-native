// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.walkmanstudio.nativeplayer.library.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun LibraryScreen(model: LibraryViewModel, playback: PlaybackViewModel, updates: com.walkmanstudio.nativeplayer.update.UpdateViewModel, showDeviceHelp: () -> Unit, exportLog: () -> Unit, chooseTree: () -> Unit) {
    val roots by model.roots.collectAsStateWithLifecycle()
    val scans by model.scans.collectAsStateWithLifecycle()
    val total by model.trackCount.collectAsStateWithLifecycle()
    val f by model.filter.collectAsStateWithLifecycle()
    val tab by model.tab.collectAsStateWithLifecycle()
    val settingsSection by model.settingsSection.collectAsStateWithLifecycle()
    val section = MioSettingsSection.fromSaved(settingsSection)
    val libraryScroll = rememberLazyListState()
    val settingsScroll = rememberLazyListState()
    LaunchedEffect(tab, section) {
        if (tab == "settings") settingsScroll.scrollToItem(0)
    }
    val browser by model.browser.collectAsStateWithLifecycle()
    val appearance by model.appearance.collectAsStateWithLifecycle()
    val summary by model.summary.collectAsStateWithLifecycle()
    val folders by model.folders.collectAsStateWithLifecycle()
    val playlists by model.playlists.collectAsStateWithLifecycle()
    val metadataProgress by model.metadataProgress.collectAsStateWithLifecycle()
    val tracks = model.tracks.collectAsLazyPagingItems()
    val albums = model.albums.collectAsLazyPagingItems()
    val snackbar = remember { SnackbarHostState() }
    val showNow by model.nowPlaying.collectAsStateWithLifecycle()
    val theme by model.walkmanTheme.collectAsStateWithLifecycle()
    val displayMode by model.nowPlayingMode.collectAsStateWithLifecycle()
    val cassetteLandscape by model.cassetteLandscape.collectAsStateWithLifecycle()
    var removeRootId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailTrack by remember { mutableStateOf<TrackEntity?>(null) }
    var playlistTrack by remember { mutableStateOf<TrackEntity?>(null) }
    var newPlaylist by remember { mutableStateOf(false) }
    var playlistName by rememberSaveable { mutableStateOf("") }
    val inCollection = tab != "settings" &&
        (f.album != null || f.playlist != null || (tab == "folders" && f.rootId != null))
    val title = when (tab) {
        "settings" -> "Cài đặt"; "favorites" -> "Yêu thích"
        "folders" -> f.folder?.substringAfterLast('/')?.ifBlank { roots.firstOrNull { it.id == f.rootId }?.displayName } ?: "Thư mục"
        else -> f.album ?: playlists.firstOrNull { it.id == f.playlist }?.name ?: "Thư viện"
    }
    LaunchedEffect(model) { model.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(playback) { playback.messages.collect {snackbar.showSnackbar(it)} }
    BackHandler(enabled = inCollection && !showNow) { model.back() }
    SharedTransitionLayout {
        AnimatedContent(targetState = showNow, label = "mini-to-now-playing") { now ->
            val sharedArt = Modifier.sharedElement(rememberSharedContentState("current-artwork"), animatedVisibilityScope = this@AnimatedContent)
            if (now) NowPlaying(playback,sharedArt,theme,displayMode,model::setWalkmanTheme, model::setNowPlayingMode, { track -> model.favorite(track) }) { model.showNowPlaying(false) }
            else Scaffold(
                topBar = { TopAppBar(title = {
                    if (tab == "library" && !inCollection) Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                    else Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                    navigationIcon = { if (inCollection) IconButton(onClick = model::back) { StudioIcon(R.drawable.ic_back, "Trở lại") } },
                    actions = { if (tab != "settings") IconButton(onClick = chooseTree) { StudioIcon(R.drawable.ic_add_folder, "Thêm thư mục nhạc") } }) },
                bottomBar = { Column {
                    PlaybackPanel(playback, sharedArt) { model.showNowPlaying(true) }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        listOf(Triple("library", "Thư viện", R.drawable.ic_library), Triple("folders", "Thư mục", R.drawable.ic_folder), Triple("favorites", "Yêu thích", R.drawable.ic_favorite), Triple("settings", "Cài đặt", R.drawable.ic_settings)).forEach { (value, label, icon) ->
                            NavigationBarItem(selected = tab == value, onClick = { model.selectTab(value) }, icon = { StudioIcon(icon) }, label = { Text(label, maxLines = 1) })
                        }
                    }
                } }, snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding), state = if (tab == "settings") settingsScroll else libraryScroll, verticalArrangement = Arrangement.spacedBy(4.dp)) {

                    if (tab == "settings") {
                        item { MioSettingsTabs(section, model::openSettings) }
                        item(key = section.name) {
                            MioSettingsContent(section, model, playback, updates, appearance, displayMode, theme,
                                cassetteLandscape, total, showDeviceHelp, exportLog, chooseTree)
                        }
                    } else if (tab == "folders" && f.rootId == null) {
                        items(roots, key = { "root-${it.id}" }) { root -> RootCard(root, scans.firstOrNull { it.rootId == root.id },
                            { model.openFolder(root.id) }, { model.rescan(root.id) }, { model.rescan(root.id, true) }, { model.cancel(root.id) }, { removeRootId = root.id }, chooseTree) }
                        item { TextButton(onClick = chooseTree, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Thêm thư mục nhạc") } }
                    } else {
                        if (roots.isEmpty() && browser != "playlists") item { MioLibraryEmpty(chooseTree) }
                        if (tab == "library" && !inCollection) item {
                            LazyRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(listOf("songs" to "Bài hát", "albums" to "Album", "playlists" to "Playlist")) { (value, label) -> FilterChip(selected = browser == value, onClick = { model.browse(value) }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp)) }
                            }
                        }
                        metadataProgress?.let { progress -> item { Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) { Text(progress, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); TextButton(onClick = { model.cancelMetadata() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Hủy") } } } }
                        items(scans.filter { it.running || it.state == "PERMISSION_LOST" }, key = { "scan-${it.rootId}" }) { status -> ScanCard(roots.firstOrNull { it.id == status.rootId }?.displayName ?: "Thư mục", status, { model.cancel(status.rootId) }, chooseTree) }
                        if (tab == "library" && browser == "albums" && !inCollection) {
                            items(albums.itemCount, key = albums.itemKey { "${it.artist}|${it.album}" }) { index -> albums[index]?.let { album ->
                                ListItem(
                                    headlineContent = { Text(album.album, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Column {
                                        Text(album.artist.ifBlank { "Không có tag nghệ sĩ" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${formatCount(album.count)} bài · ${clockTime(album.duration)}")
                                    } },
                                    leadingContent = { AlbumCover(album.coverKey, "${album.artist}|${album.album}", Modifier.size(76.dp)) },
                                    modifier = Modifier.clickable(onClickLabel = "Mở album") { model.openAlbum(album) },
                                )
                            } }
                            if (albums.loadState.refresh is LoadState.NotLoading && albums.itemCount == 0) item { Text("Chưa có album từ tag. Xem Bài hát hoặc Thư mục.", Modifier.padding(20.dp)) }
                            if (albums.loadState.refresh is LoadState.Error) item { TextButton(onClick = albums::retry) { Text("Thử đọc lại album") } }
                        } else if (tab == "library" && browser == "playlists" && !inCollection) {
                            item { TextButton(onClick = { newPlaylist = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Tạo playlist") } }
                            items(playlists, key = { "playlist-${it.id}" }) { playlist ->
                                var menu by remember { mutableStateOf(false) }
                                ListItem(headlineContent = { Text(playlist.name, fontSize = 16.sp) }, leadingContent = { StudioIcon(R.drawable.ic_library) },
                                    trailingContent = { Box {
                                        IconButton(onClick = { menu = true }) { StudioIcon(R.drawable.ic_more, "Tùy chọn playlist ${playlist.name}") }
                                        DropdownMenu(menu, { menu = false }) { DropdownMenuItem(text = { Text("Xóa playlist") }, onClick = { model.deletePlaylist(playlist.id); menu = false }) }
                                    } }, modifier = Modifier.heightIn(min = 64.dp).clickable { model.openPlaylist(playlist.id) })
                            }
                        } else if (roots.isNotEmpty() || total > 0) {
                            if (inCollection) item {
                                val first = tracks.itemSnapshotList.items.firstOrNull()
                                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TrackCover(first, Modifier.size(112.dp))
                                    Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(title, style = MaterialTheme.typography.headlineSmall)
                                        Text("${formatCount(summary.count)} bài · ${clockTime(summary.duration)}", fontSize = 13.sp)
                                        if (f.folder != null) Text(f.folder?.ifBlank { "Thư mục gốc" }.orEmpty(), fontSize = 13.sp)
                                    }
                                }
                            }
                            item {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(onClick = { playback.playSelection(f.copy(recursive = true)) }, enabled = summary.count > 0, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { StudioIcon(R.drawable.ic_play); Text("Phát tất cả") }
                                    OutlinedButton(onClick = { playback.playSelection(f.copy(recursive = true), shuffle = true) }, enabled = summary.count > 0, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { StudioIcon(R.drawable.ic_shuffle); Text("Ngẫu nhiên") }
                                }
                            }
                            if (tab == "folders") items(folders, key = { "folder-${it.path}" }) { folder ->
                                ListItem(headlineContent = { Text(folder.path.substringAfterLast('/'), fontSize = 16.sp) }, supportingContent = { Text("${formatCount(folder.count)} bài · ${clockTime(folder.duration)}", fontSize = 13.sp) }, leadingContent = { StudioIcon(R.drawable.ic_folder) }, modifier = Modifier.heightIn(min = 64.dp).clickable { model.openFolder(f.rootId!!, folder.path) })
                            }
                            item { OutlinedTextField(value = f.query, onValueChange = model::setQuery, label = { Text("Tìm bài hát, nghệ sĩ, album") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(16.dp), trailingIcon = { if (f.query.isNotEmpty()) IconButton(onClick = { model.setQuery("") }) { StudioIcon(R.drawable.ic_close, "Xóa tìm kiếm") } }) }
                            if (tracks.loadState.refresh is LoadState.Loading && tracks.itemCount == 0) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp)) }
                            if (tracks.loadState.refresh is LoadState.Error) item { TextButton(onClick = tracks::retry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Chưa đọc được danh sách · Thử lại") } }
                            if (tracks.loadState.refresh is LoadState.NotLoading && tracks.itemCount == 0 && folders.isEmpty()) item { Text(if (f.query.isNotBlank()) "Không tìm thấy bài hát phù hợp." else "Chưa có bài hát trong danh sách này.", Modifier.padding(20.dp)) }
                            items(tracks.itemCount, key = tracks.itemKey { "track-${it.id}" }) { index -> tracks[index]?.let { track ->
                                TrackRow(track, { playback.play(track, f) }, { detailTrack = track }, { model.favorite(track) }, { playback.add(track) }, { playlistTrack = track }, if (f.playlist != null) ({ model.removeFromPlaylist(f.playlist!!, track.id) }) else null)
                            } }
                            if (tracks.loadState.append is LoadState.Error) item { TextButton(onClick = tracks::retry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Tải tiếp danh sách") } }
                        }
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }
            }
        }
    }
    roots.firstOrNull { it.id == removeRootId }?.let { root -> AlertDialog(onDismissRequest = { removeRootId = null }, title = { Text("Bỏ thư mục khỏi thư viện?") }, text = { Text("${root.displayName}\nChỉ bỏ chỉ mục. Tệp gốc vẫn ở trên máy.") }, confirmButton = { TextButton(onClick = { model.remove(root.id); removeRootId = null }) { Text("Bỏ thư mục") } }, dismissButton = { TextButton(onClick = { removeRootId = null }) { Text("Giữ lại") } }) }
    detailTrack?.let { track -> AlertDialog(onDismissRequest = { detailTrack = null }, title = { Text("Chi tiết bài hát") }, text = {
        SelectionContainer { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DetailField("Tên tệp gốc", track.name); DetailField("Tên bài", track.title ?: "Không có tag")
            DetailField("Nghệ sĩ", track.artist ?: "Không có tag"); DetailField("Album", track.album ?: "Không có tag")
            DetailField("Disc / Track", "${track.discNumber ?: "—"} / ${track.trackNumber ?: "—"}")
            DetailField("ReplayGain track / album", "${track.trackGain ?: "—"} / ${track.albumGain ?: "—"} dB")
            DetailField("Thời lượng", track.durationMs?.let(::clockTime) ?: "Chưa rõ")
            DetailField("Đường dẫn", track.relativePath); DetailField("Kích thước", formatSize(track.size))
            DetailField("MIME nguồn", track.mimeType); DetailField("URI nguồn", track.uri)
            track.metadataError?.let { DetailField("Lỗi đọc tag", it) }
        } }
    }, confirmButton = { TextButton(onClick = { detailTrack = null }) { Text("Đóng") } }) }
    if (playlistTrack != null && !newPlaylist) AlertDialog(onDismissRequest = { playlistTrack = null }, title = { Text("Thêm vào playlist") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            playlists.forEach { playlist -> TextButton(onClick = { model.addToPlaylist(playlist.id, playlistTrack!!.id); playlistTrack = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(playlist.name) } }
            TextButton(onClick = { newPlaylist = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Tạo playlist mới") }
        }
    }, confirmButton = { TextButton(onClick = { playlistTrack = null }) { Text("Đóng") } })
    if (newPlaylist) AlertDialog(onDismissRequest = { newPlaylist = false }, title = { Text("Tạo playlist") }, text = { OutlinedTextField(value = playlistName, onValueChange = { playlistName = it.take(120) }, label = { Text("Tên playlist") }, singleLine = true) }, confirmButton = {
        TextButton(enabled = playlistName.isNotBlank(), onClick = { model.createPlaylist(playlistName, playlistTrack?.id); newPlaylist = false; playlistTrack = null; playlistName = "" }) { Text("Tạo") }
    }, dismissButton = { TextButton(onClick = { newPlaylist = false }) { Text("Hủy") } })
}

@Composable
private fun MioLibraryEmpty(chooseTree: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(MioSpacing.gutter),
        verticalArrangement = Arrangement.spacedBy(MioSpacing.group)) {
        StudioIcon(R.drawable.ic_library, modifier = Modifier.size(32.dp))
        Text("Thư viện của bạn", style = MaterialTheme.typography.headlineMedium)
        Text("Chọn nơi lưu nhạc để bắt đầu.", style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = chooseTree, modifier = Modifier.heightIn(min = MioSpacing.touch)) { Text("Chọn thư mục") }
    }
}

@Composable
private fun TrackRow(track: TrackEntity, play: () -> Unit, details: () -> Unit, favorite: () -> Unit, enqueue: () -> Unit, playlist: () -> Unit, remove: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Phát bài hát", onClick = play).heightIn(min = 72.dp).padding(start = MioSpacing.gutter, end = 4.dp, top = MioSpacing.compact, bottom = MioSpacing.compact), verticalAlignment = Alignment.CenterVertically) {
        TrackCover(track, Modifier.size(44.dp)); Spacer(Modifier.width(MioSpacing.group))
        Column(Modifier.weight(1f)) {
            Text(track.title ?: track.name.substringBeforeLast('.', track.name), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(track.artist ?: "Không có tag nghệ sĩ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(track.durationMs?.let(::clockTime) ?: "—", fontSize = 13.sp)
        Box {
            IconButton(onClick = { menu = true }) { StudioIcon(R.drawable.ic_more, "Tùy chọn ${track.title ?: track.name}") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(if (track.isFavorite) "Bỏ yêu thích" else "Yêu thích") }, onClick = { menu = false; favorite() })
                DropdownMenuItem(text = { Text("Thêm hàng đợi") }, onClick = { menu = false; enqueue() })
                DropdownMenuItem(text = { Text("Thêm vào playlist") }, onClick = { menu = false; playlist() })
                if (remove != null) DropdownMenuItem(text = { Text("Bỏ khỏi playlist") }, onClick = { menu = false; remove() })
                DropdownMenuItem(text = { Text("Chi tiết") }, onClick = { menu = false; details() })
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun RootCard(root: LibraryRootEntity, status: ScanStatus?, open: () -> Unit, rescan: () -> Unit, force: () -> Unit, cancel: () -> Unit, remove: () -> Unit, chooseTree: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(root.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "Tùy chọn thư mục ${root.displayName}" }) { Text("⋮", fontSize = 24.sp) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Quét lại toàn bộ thông tin tệp") }, onClick = { menu = false; force() }, enabled = status?.running != true)
                        DropdownMenuItem(text = { Text("Bỏ thư mục khỏi thư viện") }, onClick = { menu = false; remove() })
                    }
                }
            }
            if (root.permissionLost) Text("Cần cấp lại quyền đọc thư mục.", color = MaterialTheme.colorScheme.error)
            Text(formatScanState(status?.state ?: "NOT_SCANNED"), style = MaterialTheme.typography.bodyMedium)
            status?.let {
                Text("Đã xét ${formatCount(it.visited)} mục · ${formatCount(it.audioFiles)} tệp nhạc", style = MaterialTheme.typography.bodyMedium)
                if (it.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                val message = it.message
                if (!message.isNullOrBlank()) Text(message, style = MaterialTheme.typography.bodyMedium)
            }
            // Separate rows stay usable at 200% font scale and in narrow windows.
            TextButton(onClick = open, modifier = Modifier.heightIn(min = 48.dp)) { Text("Xem danh sách tệp") }
            if (status?.running == true) {
                TextButton(onClick = cancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Hủy quét") }
            } else {
                TextButton(onClick = if (root.permissionLost) chooseTree else rescan, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (root.permissionLost) "Cấp lại quyền đọc" else "Quét lại thư mục")
                }
            }
        }
    }
}

@Composable
private fun ScanCard(name: String, status: ScanStatus, cancel: () -> Unit, chooseTree: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("${formatScanState(status.state)} · ${formatCount(status.audioFiles)} tệp nhạc", color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (status.running) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = cancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Hủy quét") }
            } else {
                TextButton(onClick = chooseTree, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cấp lại quyền đọc") }
            }
        }
    }
}

@Composable
private fun DetailField(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 16.sp)
    }
}
