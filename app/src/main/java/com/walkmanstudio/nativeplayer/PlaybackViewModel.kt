// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import com.walkmanstudio.nativeplayer.library.LibraryDatabase
import com.walkmanstudio.nativeplayer.library.TrackEntity
import com.walkmanstudio.nativeplayer.library.TrackFilter
import com.walkmanstudio.nativeplayer.playback.PlaybackCommands
import com.walkmanstudio.nativeplayer.playback.DspSettings
import com.walkmanstudio.nativeplayer.playback.DsdLevelSettings
import com.walkmanstudio.nativeplayer.core.DspConfig
import com.walkmanstudio.nativeplayer.playback.LifecycleLog
import com.walkmanstudio.nativeplayer.playback.PlaybackService
import com.walkmanstudio.nativeplayer.playback.asMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SignalUi(val source:String="Chưa có nguồn",val decoded:String="Chưa có PCM",val output:String="Chưa đo được ngõ ra",val decoder:String="",val gainDb:Double=0.0,val clipped:Long=0,val dsdOverFullScaleSamples:Long=0,
    val sourceMime:String?=null,val sourceRate:Int?=null,val sourcePcmEncoding:Int?=null,
    val averageBitrate:Int?=null,val peakBitrate:Int?=null,val sourceDsdMultiplier:Int?=null,val usbStatus:String?=null)

data class UsbSettingsUi(val available:Boolean=false,val requested:Boolean=false,val verified:Boolean=false,
    val connected:Boolean=false,val warningAccepted:Boolean=false,val reason:String="Đang chờ thông tin thiết bị")

data class PlaybackUi(
    val connected: Boolean = false, val id: String? = null, val title: String = "",
    val artist: String = "", val playing: Boolean = false, val requested: Boolean = false,
    val positionMs: Long = 0, val durationMs: Long = 0, val queueCount: Int = 0,
    val error: String? = null, val detail: String? = null, val sleepRemainingMs: Long = 0, val repeatOne: Boolean = false,
    val queueIndex: Int = 0, val shuffled: Boolean = false,
    val album: String = "", val seekEnabled: Boolean = false, val timelineKnown: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
)

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackViewModel(context: Context) : ViewModel() {
    private val app = context.applicationContext
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pending: (() -> Unit)? = null
    private val mutable = MutableStateFlow(PlaybackUi())
    val state = mutable.asStateFlow()
    val messages=MutableSharedFlow<String>(extraBufferCapacity=8)
    val dsp = MutableStateFlow(DspSettings.read(app))
    val dsdGainDb = MutableStateFlow(DsdLevelSettings.read(app))
    val signal = MutableStateFlow(SignalUi())
    val usb = MutableStateFlow(UsbSettingsUi())
    private val currentId = MutableStateFlow<Long?>(null)
    val currentTrack = currentId.flatMapLatest { id -> if (id == null) flowOf(null) else LibraryDatabase.getInstance(app).libraryDao().observeTrack(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val queue = MutableStateFlow<List<MediaItem>>(emptyList())
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { publish(); queue.value = (0 until player.mediaItemCount).map(player::getMediaItemAt) }
    }
    init { viewModelScope.launch { while (true) { delay(500); publish() } } }
    fun connect() {
        if (future != null) return
        val connecting = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java)))
            .setListener(object: MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    if(this@PlaybackViewModel.controller === controller) disconnect()
                }
            }).buildAsync()
        future = connecting
        connecting.addListener({
            if (future !== connecting) return@addListener
            try {
                controller = connecting.get().also { it.addListener(listener) }
                publish(); queue.value = (0 until controller!!.mediaItemCount).map(controller!!::getMediaItemAt); pending?.invoke(); pending = null
            } catch (_: Exception) {
                invalidateUsbConfirmation()
                future = null; mutable.value = mutable.value.copy(connected = false, error = "Chưa kết nối được trình phát.")
            }
        }, ContextCompat.getMainExecutor(app))
    }
    fun disconnect() {
        controller?.removeListener(listener); controller = null
        future?.let { MediaController.releaseFuture(it) }; future = null
        invalidateUsbConfirmation()
        mutable.value = mutable.value.copy(connected = false)
    }
    private fun publish() {
        val player = controller ?: return
        if(!player.isConnected) {invalidateUsbConfirmation();return}
        val extra=player.sessionExtras
        if(extra.containsKey("dsp")) dsp.value=DspSettings.decode(extra.getString("dsp"))
        if(extra.containsKey("dsdGainDb")) dsdGainDb.value=extra.getInt("dsdGainDb")
        usb.value=UsbSettingsUi(extra.getBoolean("usbAvailable"),extra.getBoolean("usbRequested"),extra.getBoolean("usbVerified"),
            extra.getBoolean("usbConnected"),extra.getBoolean("usbWarningAccepted"),extra.getString("usbReason") ?: "Đang chờ thông tin thiết bị")
        // Read only the currently selected audio Format. The service remains the sole player owner.
        val format=player.currentTracks.groups.asSequence().filter { it.type==C.TRACK_TYPE_AUDIO }
            .flatMap { group -> (0 until group.length).asSequence().filter(group::isTrackSelected).map(group::getTrackFormat) }.firstOrNull()?.takeIf { player.playbackState != Player.STATE_IDLE }
        signal.value=SignalUi(extra.getString("source").orEmpty(),extra.getString("decoded").orEmpty(),if(format!=null) extra.getString("output").orEmpty() else "",extra.getString("decoder").orEmpty(),extra.getDouble("gainDb"),extra.getLong("clipped"),extra.getLong("dsdOverFullScaleSamples"),
            sourceMime=format?.sampleMimeType,sourceRate=format?.sampleRate?.takeIf { it>0 },sourcePcmEncoding=format?.pcmEncoding,
            averageBitrate=format?.averageBitrate?.takeIf { it>0 },peakBitrate=format?.peakBitrate?.takeIf { it>0 },
            sourceDsdMultiplier=format?.let { CassetteSignal.dsdMultiplier(it.id,it.sampleMimeType,it.labels.map { label -> label.value }) },
            usbStatus=if(usb.value.connected) if(usb.value.verified) "Bit-perfect USB" else "USB · Mixer hệ thống" else null)
        val error = player.playerError
        val id = player.currentMediaItem?.mediaId
        currentId.value = id?.toLongOrNull()
        // Room's asynchronous observer can still contain the previous item for one frame.
        // Never combine that item's duration/tags with the newly selected media item.
        val stored = currentTrack.value?.takeIf { it.id.toString() == id }
        val metadata = player.mediaMetadata
        val text = NowPlayingText.display(stored?.title, stored?.artist, stored?.album, stored?.name,
            metadata.title?.toString(), metadata.artist?.toString(), metadata.albumTitle?.toString())
        val timeline = PlaybackTimeline.snapshot(player.duration, stored?.durationMs ?: metadata.durationMs,
            player.currentPosition, player.playbackState, player.isCurrentMediaItemSeekable)
        mutable.value = PlaybackUi(connected=true, id=id,
            title=text.title, artist=text.artist, album=text.album,
            playing=player.isPlaying, requested=player.playWhenReady, playbackState=player.playbackState, positionMs=timeline.positionMs,
            durationMs=timeline.durationMs, seekEnabled=timeline.seekEnabled, timelineKnown=timeline.timelineKnown, queueCount=player.mediaItemCount,
            error=if (error != null) "Chưa đọc được bài hát" else null,
            detail=error?.let { "${it.errorCodeName} (${it.errorCode}); ${it.cause?.javaClass?.simpleName.orEmpty()}; ${(it.cause?.message ?: it.message).orEmpty().take(500)}" },
            sleepRemainingMs=(player.sessionExtras.getLong("sleepAt", 0) - SystemClock.elapsedRealtime()).coerceAtLeast(0), repeatOne=player.repeatMode == Player.REPEAT_MODE_ONE,
            queueIndex=player.currentMediaItemIndex.takeIf { it in 0 until player.mediaItemCount }?.plus(1) ?: 0,
            shuffled=player.shuffleModeEnabled)
    }
    fun play(track: TrackEntity, filter: TrackFilter = TrackFilter(rootId = track.rootId)) = playSelection(filter, track.id)
    private fun invalidateUsbConfirmation() {
        usb.value=UsbSettingsUi(reason="Chưa kết nối trình phát")
        signal.value=signal.value.copy(usbStatus=null)
    }
    fun playSelection(filter: TrackFilter, anchor: Long = 0, shuffle: Boolean = false) {
        if (controller == null) { pending = { playSelection(filter, anchor, shuffle) }; connect(); return }
        val selected = if (shuffle) filter.copy(shuffleSeed = kotlin.random.Random.nextInt(1, Int.MAX_VALUE)) else filter.copy(shuffleSeed = null)
        send(PlaybackCommands.PLAY_SELECTION, Bundle().apply { putString("filter", selected.encode()); putLong("anchor", if (shuffle) 0 else anchor) })
    }
    private fun send(action: String, args: Bundle) {
        val player = controller ?: return
        val result = player.sendCustomCommand(PlaybackCommands.command(action), args)
        result.addListener({
            try { if (result.get().resultCode != SessionResult.RESULT_SUCCESS) mutable.value = mutable.value.copy(error = "Chưa thực hiện được yêu cầu phát.") }
            catch (_: Exception) { mutable.value = mutable.value.copy(error = "Chưa thực hiện được yêu cầu phát.") }
        }, ContextCompat.getMainExecutor(app))
    }
    fun add(track: TrackEntity) { controller?.addMediaItem(MediaItem.Builder().setMediaId(track.id.toString()).build()) }
    fun remove(index: Int) { controller?.let { if (index in 0 until it.mediaItemCount) it.removeMediaItem(index) } }
    fun queuePlay(index: Int) { controller?.apply { if (index in 0 until mediaItemCount) { seekToDefaultPosition(index); prepare(); play() } } }
    fun repeatOne() { controller?.apply { repeatMode = if (repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE } }
    /** This controls the actual loaded queue window, not a fabricated whole-library shuffle state. */
    fun toggleShuffle() { controller?.apply { shuffleModeEnabled = !shuffleModeEnabled } }
    fun sleep(minutes: Int) { send(PlaybackCommands.SLEEP, Bundle().apply { putInt("minutes", minutes) }) }
    fun setDsp(config:DspConfig) {
        dsp.value=config
        if(controller==null) { pending={setDsp(config)};connect();return }
        send(PlaybackCommands.DSP,Bundle().apply {putString("config",DspSettings.encode(config))})
    }
    fun toggle() {
        val player = controller ?: return
        if (player.playerError != null) retry()
        else if (player.playWhenReady) player.pause() else { player.prepare(); player.play() }
    }
    fun setDsdGain(db: Int) {
        require(db in com.walkmanstudio.nativeplayer.playback.DsdLevelState.ALLOWED_GAINS_DB)
        dsdGainDb.value=db
        if(controller==null) { pending={setDsdGain(db)}; connect(); return }
        send(PlaybackCommands.DSD_LEVEL, Bundle().apply { putInt("gainDb", db) })
    }
    fun previous() { controller?.seekToPreviousMediaItem() }
    fun setUsbOutput(enabled:Boolean, warningAccepted:Boolean) {
        if(controller==null) {pending={setUsbOutput(enabled,warningAccepted)};connect();return}
        send(PlaybackCommands.USB_OUTPUT,Bundle().apply{putBoolean("enabled",enabled);putBoolean("warningAccepted",warningAccepted)})
    }
    fun next() { controller?.seekToNextMediaItem() }
    fun seek(positionMs: Long) {
        val player = controller ?: return
        val stored = currentTrack.value?.takeIf { it.id.toString() == player.currentMediaItem?.mediaId }
        // Recheck the live controller: a touch can finish after the queue becomes idle.
        val target = PlaybackTimeline.seekTarget(positionMs, player.duration,
            stored?.durationMs ?: player.mediaMetadata.durationMs, player.currentPosition,
            player.playbackState, player.isCurrentMediaItemSeekable) ?: return
        player.seekTo(target)
    }
    fun retry() {
        controller?.apply {
            if (mediaItemCount == 0) return
            val items = (0 until mediaItemCount).map { getMediaItemAt(it) }
            val index = currentMediaItemIndex; val position = currentPosition.coerceAtLeast(0)
            setMediaItems(items, index, position); prepare(); play()
        }
    }
    fun export(uri: Uri) { LifecycleLog.get(app).export(uri) { ok ->
        viewModelScope.launch { messages.emit(if(ok)"Đã xuất log trình phát." else "Không xuất được log.") }
    } }
    override fun onCleared() { disconnect(); super.onCleared() }
}
