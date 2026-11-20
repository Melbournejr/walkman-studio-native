// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import com.walkmanstudio.nativeplayer.library.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LibraryViewModel(private val repository: LibraryRepository, private val savedState: SavedStateHandle, context: Context) : ViewModel() {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    init { viewModelScope.launch { repository.indexMissingMetadata() } }
    val tab = savedState.getStateFlow("tab", "library")
    val browser = savedState.getStateFlow("browser", "songs")
    private val encoded = savedState.getStateFlow("selection", TrackFilter().encode())
    val filter = encoded.map { TrackFilter.decode(it) ?: TrackFilter() }.stateIn(viewModelScope, SharingStarted.Eagerly, TrackFilter.decode(encoded.value) ?: TrackFilter())
    val nowPlaying = savedState.getStateFlow("nowPlaying",false)
    val walkmanTheme = MutableStateFlow(WalkmanTheme.find(preferences.getString("walkman","tpsl2")))
    val nowPlayingMode = MutableStateFlow(PlayerDisplaySettings.read(app))
    val cassetteLandscape = MutableStateFlow(PlayerDisplaySettings.cassetteLandscape(app))
    fun setCassetteLandscape(enabled: Boolean) {
        cassetteLandscape.value = enabled
        PlayerDisplaySettings.setCassetteLandscape(app, enabled)
    }
    fun setNowPlayingMode(mode: NowPlayingMode) {
        nowPlayingMode.value = mode
        PlayerDisplaySettings.write(app, mode)
    }
    fun showNowPlaying(value:Boolean) {savedState["nowPlaying"]=value}
    fun setWalkmanTheme(theme:WalkmanTheme){walkmanTheme.value=theme;preferences.edit().putString("walkman",theme.id).apply()}
    val appearance = MutableStateFlow(preferences.getString("mode", "system") ?: "system")
    private val notices = Channel<String>(Channel.BUFFERED)
    val messages = notices.receiveAsFlow()
    val roots = repository.roots.catch { notices.send("Chưa đọc được danh sách thư mục."); emit(emptyList()) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val trackCount = repository.trackCount.catch { emit(0L) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    val scans = repository.scans.catch { emit(emptyList()) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val metadataProgress = repository.metadataProgress.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val playlists = repository.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val albums = repository.albums.cachedIn(viewModelScope)
    val tracks = filter.debounce(200).flatMapLatest(repository::selection).cachedIn(viewModelScope)
    val summary = filter.flatMapLatest { repository.summary(it.copy(recursive = true)) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SelectionSummary(0, 0))
    val folders = filter.flatMapLatest { f -> val root = f.rootId; val path = f.folder; if (root != null && path != null) repository.folders(root, path) else flowOf(emptyList()) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private fun change(value: TrackFilter) { savedState["selection"] = value.encode() }
    fun setQuery(value: String) { change(filter.value.copy(query = value.take(512))) }
    fun selectTab(value: String) {
        require(value in setOf("library", "folders", "favorites", "settings"))
        if (tab.value != value && value != "settings") { change(TrackFilter(favorites = value == "favorites")); savedState["browser"] = "songs" }
        savedState["tab"] = value
    }
    fun browse(value: String) { savedState["browser"] = value; change(TrackFilter()) }
    fun openFolder(root: String, path: String = "") { savedState["tab"] = "folders"; change(TrackFilter(rootId = root, folder = path, recursive = false)) }
    fun openAlbum(album: AlbumSummary) { savedState["tab"] = "library"; savedState["browser"] = "songs"; change(TrackFilter(album = album.album, albumArtist = album.artist)) }
    fun openPlaylist(id: Long) { savedState["tab"] = "library"; savedState["browser"] = "songs"; change(TrackFilter(playlist = id)) }
    fun back() {
        val f = filter.value
        val path = f.folder; val root = f.rootId
        if (path != null && root != null) {
            if (path.isEmpty()) change(TrackFilter()) else openFolder(root, path.substringBeforeLast('/', ""))
        } else { change(TrackFilter()); savedState["browser"] = if (f.playlist != null) "playlists" else if (f.album != null) "albums" else "songs" }
    }
    fun setAppearance(value: String) { require(value in setOf("system", "light", "dark")); appearance.value = value; preferences.edit().putString("mode", value).apply() }
    fun addTree(uri: Uri) = action { val root = repository.addTree(uri); openFolder(root.id); notices.send("Đã thêm ${root.displayName}. Đang quét.") }
    fun rescan(root: String, force: Boolean = false) = action { repository.rescan(root, force) }
    fun cancelMetadata() = action { repository.cancelMetadata() }
    fun cancel(root: String) = action { repository.cancelScan(root) }
    fun remove(root: String) = action { repository.removeRoot(root); if (filter.value.rootId == root) change(TrackFilter()); notices.send("Đã bỏ chỉ mục; giữ nguyên tệp nhạc gốc.") }
    fun favorite(track: TrackEntity) = action { repository.favorite(track.id) }
    fun createPlaylist(name: String, track: Long? = null) = action { val id = repository.createPlaylist(name); if (track != null) repository.addToPlaylist(id, track) }
    fun addToPlaylist(id: Long, track: Long) = action { repository.addToPlaylist(id, track); notices.send("Đã thêm vào playlist.") }
    fun removeFromPlaylist(id: Long, track: Long) = action { repository.removeFromPlaylist(id, track) }
    fun deletePlaylist(id: Long) = action { repository.deletePlaylist(id); if (filter.value.playlist == id) back() }
    fun reportPickerError() = action { notices.send("Không mở được trình chọn thư mục.") }
    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (cancel: CancellationException) { throw cancel }
        catch (_: SecurityException) { notices.send("Không còn quyền đọc. Hãy chọn lại thư mục.") }
        catch (_: Exception) { notices.send("Chưa thực hiện được thao tác. Hãy thử lại.") }
    }
}
