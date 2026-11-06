// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback

import android.app.PendingIntent
import android.os.Build
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.exoplayer.DefaultLoadControl
import com.walkmanstudio.nativeplayer.core.DsdHeader
import com.walkmanstudio.nativeplayer.playback.dsd.DsdExtractor
import android.content.Intent
import android.os.Handler
import android.os.Bundle
import android.os.SystemClock
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.DecoderReuseEvaluation
import com.walkmanstudio.nativeplayer.core.ReplayGainInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionError
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.walkmanstudio.nativeplayer.library.LibraryDatabase
import com.walkmanstudio.nativeplayer.library.TrackFilter
import com.walkmanstudio.nativeplayer.library.ArtworkCache
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The service, never the Activity, owns the one playlist/decoder/audio-focus session. */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    private lateinit var log: LifecycleLog
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var dsp: DspState
    private lateinit var dsdLevel: DsdLevelState
    private var renderRevision=0L
    private var sourceSignal:Format?=null
    private var decodedSignal:Format?=null
    private var outputSignal:AudioSink.AudioTrackConfig?=null
    private var decoderName="Chưa khởi tạo decoder"
    private var selection: TrackFilter? = null
    private var metadataWatch: Job? = null
    private var watchedId: Long? = null
    private var sleepAt = 0L
    private val sleepTick = Runnable {
        sleepAt = 0L; player.pause(); log.record("sleep_timer_expired")
        updateSessionExtras()
    }
    private var queueRevision = 0L
    private var appending = false
    private var catalogEnded = false
    private var stopReason = "system_callback_unknown_initiator"
    private val persistTick = object : Runnable {
        override fun run() { savePosition();updateSessionExtras(); mainHandler.postDelayed(this, 2_000) }
    }
    private val playerEvents=object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                log.record("player_state", "state=$playbackState; playWhenReady=${player.playWhenReady}")
                savePosition()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                val event = when (reason) {
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "audio_focus_loss"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "audio_becoming_noisy"
                    else -> "play_when_ready"
                }
                log.record(event, "requested=$playWhenReady; reason=$reason"); savePosition()
            }
            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                log.record("audio_focus_suppression", "reason=$playbackSuppressionReason")
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                log.record("media_transition", "id=${mediaItem?.mediaId}; reason=$reason")
                savePosition(); observeCurrentMetadata(); appendCatalogWindow()
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                log.record("position_discontinuity", "positionMs=${newPosition.positionMs}; reason=$reason"); savePosition()
            }
            override fun onPlayerError(error: PlaybackException) {
                log.record("playback_error", "id=${player.currentMediaItem?.mediaId}; code=${error.errorCode}; cause=${error.cause?.javaClass?.simpleName}")
                savePosition()
            }
        }
    private fun buildPlayer():ExoPlayer {
        val revision=++renderRevision
        val renderers=StudioRenderersFactory(this,dsp) { format -> mainHandler.post {
            if(revision==renderRevision) { decodedSignal=format;if(format.customData is DsdHeader)decoderName="C++ Kaiser FIR DSD → PCM";updateSessionExtras() }
        } }
        val baseExtractors=DefaultExtractorsFactory().setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
        val extractors=ExtractorsFactory { arrayOf<androidx.media3.extractor.Extractor>(DsdExtractor(levelState=dsdLevel) { event,detail -> log.record(event,"$detail; model=${Build.MODEL}; sdk=${Build.VERSION.SDK_INT}; abi=${Build.SUPPORTED_ABIS.firstOrNull()}") }) + baseExtractors.createExtractors() }
        val result=ExoPlayer.Builder(this,renderers)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(10000,30000,500,1000).setTargetBufferBytes(8*1024*1024).setPrioritizeTimeOverSizeThresholds(false).build())
            .setMediaSourceFactory(DefaultMediaSourceFactory(SafAudioDataSource.factory(this),extractors))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build()
        result.trackSelectionParameters=result.trackSelectionParameters.buildUpon().setAudioOffloadPreferences(
            TrackSelectionParameters.AudioOffloadPreferences.Builder().setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED).build()).build()
        result.addListener(playerEvents)
        result.addAnalyticsListener(object:AnalyticsListener {
            override fun onAudioInputFormatChanged(eventTime:AnalyticsListener.EventTime,format:Format,decoderReuseEvaluation:DecoderReuseEvaluation?) {
                if(revision==renderRevision) { sourceSignal=format;updateSessionExtras() }
            }
            override fun onAudioDecoderInitialized(eventTime:AnalyticsListener.EventTime,name:String,initializedTimestampMs:Long,initializationDurationMs:Long) {
                if(revision==renderRevision) { decoderName=name;log.record("audio_decoder",name);updateSessionExtras() }
            }
            override fun onAudioTrackInitialized(eventTime:AnalyticsListener.EventTime,audioTrackConfig:AudioSink.AudioTrackConfig) {
                if(revision==renderRevision) {
                    outputSignal=audioTrackConfig;log.record("audio_track", "rate=${audioTrackConfig.sampleRate}; encoding=${audioTrackConfig.encoding}; offload=${audioTrackConfig.offload}")
                    updateSessionExtras()
                }
            }
            override fun onAudioTrackReleased(eventTime:AnalyticsListener.EventTime,audioTrackConfig:AudioSink.AudioTrackConfig) {
                if(revision==renderRevision) { outputSignal=null;updateSessionExtras() }
            }
            override fun onAudioSinkError(eventTime:AnalyticsListener.EventTime,audioSinkError:Exception) { log.record("audio_sink_error",audioSinkError.javaClass.simpleName) }
        })
        return result
    }
    private fun rebuildForDspRoute() {
        val old=player;val items=(0 until old.mediaItemCount).map(old::getMediaItemAt)
        val index=old.currentMediaItemIndex.coerceAtLeast(0);val position=old.currentPosition.coerceAtLeast(0)
        val requested=old.playWhenReady;val repeat=old.repeatMode
        queueRevision++;old.removeListener(playerEvents);old.pause()
        sourceSignal=null;decodedSignal=null;outputSignal=null;decoderName="Chưa khởi tạo decoder"
        player=buildPlayer();session?.setPlayer(player);old.release()
        player.repeatMode=repeat
        if(items.isNotEmpty()) { player.setMediaItems(items,index.coerceAtMost(items.lastIndex),position);if(requested) {player.prepare();player.play()} }
        log.record("dsp_route_rebuilt","sourceDirect=${dsp.config.get().sourceDirect}; positionMs=$position")
    }
    override fun onCreate() {
        super.onCreate()
        log = LifecycleLog.get(this)
        log.record("service_create")
        log.recordHistoricalExits()
        selection = TrackFilter.decode(PlaybackMemory.preferences(this).getString("selection", null))
        dsp=DspState(DspSettings.read(this))
        dsdLevel=DsdLevelState(DsdLevelSettings.read(this))
        player=buildPlayer()
        val builder = MediaLibrarySession.Builder(this, player, SessionCallback())
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            builder.setSessionActivity(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        session = builder.build()
        updateSessionExtras()
        setListener(object : MediaSessionService.Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                log.record("foreground_start_rejected"); player.pause()
            }
        })
        io.launch {
            ArtworkCache.changes.collect { key ->
                val id=withContext(Dispatchers.Main){player.currentMediaItem?.mediaId?.toLongOrNull()}
                val track=id?.let{LibraryDatabase.getInstance(this@PlaybackService).libraryDao().getTrackById(it)}
                if(track?.coverKey==key) {
                    val item=track.asMediaItem(this@PlaybackService)
                    withContext(Dispatchers.Main){
                        if(player.currentMediaItem?.mediaId==item.mediaId) {
                            val current=player.currentMediaItem!!
                            if(current.mediaMetadata!=item.mediaMetadata)player.replaceMediaItem(player.currentMediaItemIndex,current.buildUpon().setMediaMetadata(item.mediaMetadata).build())
                        }
                    }
                }
            }
        }
        mainHandler.post(persistTick)
        val revision = queueRevision
        io.launch {
            val restored = readMemory()
            withContext(Dispatchers.Main) {
                if (queueRevision == revision && player.mediaItemCount == 0 && restored.mediaItems.isNotEmpty()) {
                    player.setMediaItems(restored.mediaItems, restored.startIndex, restored.startPositionMs)
                    // Restore metadata/position without starting audio or requesting focus.
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log.record("service_start_command", "action=${intent?.action?.take(80)}; flags=$flags; startId=$startId")
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val keep = keepServiceAfterTaskRemoved(player.playWhenReady, player.mediaItemCount, player.playbackState)
        log.record("task_removed", "keep_playback=$keep")
        savePosition()
        if (!keep) { stopReason = "task_removed_without_requested_playback"; stopSelf() }
    }

    override fun onDestroy() {
        if (::player.isInitialized) {
            savePosition(); log.record("service_destroy", "reason=$stopReason")
            mainHandler.removeCallbacksAndMessages(null)
            session?.release(); session = null; player.release()
        }
        metadataWatch?.cancel(); io.cancel()
        super.onDestroy()
    }

    private fun savePosition() {
        if (!::player.isInitialized || player.mediaItemCount == 0) return
        val start = (player.currentMediaItemIndex - 50).coerceAtLeast(0)
        val end = minOf(player.mediaItemCount, start + PlaybackMemory.MAX_QUEUE)
        val ids = (start until end).mapNotNull { player.getMediaItemAt(it).mediaId.toLongOrNull() }
        if (ids.isNotEmpty()) PlaybackMemory.preferences(this).edit().putString("snapshot",
            PlaybackMemory(ids, (player.currentMediaItemIndex - start).coerceIn(ids.indices), player.currentPosition.coerceAtLeast(0), player.shuffleModeEnabled, player.repeatMode).encode()).apply()
    }

    private suspend fun readMemory(): MediaSession.MediaItemsWithStartPosition {
        val memory = PlaybackMemory.decode(PlaybackMemory.preferences(this).getString("snapshot", null))
            ?: return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
        val dao = LibraryDatabase.getInstance(this).libraryDao()
        val pairs = memory.ids.mapIndexedNotNull { index, id -> dao.getTrackById(id)?.let { index to it.asMediaItem(this@PlaybackService) } }
        val restoredIndex = pairs.indexOfFirst { it.first == memory.index }.coerceAtLeast(0)
        val position = if (pairs.getOrNull(restoredIndex)?.first == memory.index) memory.positionMs else 0L
        return MediaSession.MediaItemsWithStartPosition(pairs.map { it.second }, restoredIndex, position)
    }

    private fun appendCatalogWindow() {
        if (catalogEnded || appending || player.repeatMode == Player.REPEAT_MODE_ONE || player.mediaItemCount == 0) return
        if (player.mediaItemCount - player.currentMediaItemIndex > 15) return
        val filter = selection ?: return
        val tail = player.getMediaItemAt(player.mediaItemCount - 1).mediaId.toLongOrNull() ?: return
        val revision = queueRevision; appending = true
        io.launch {
            try {
                val dao = LibraryDatabase.getInstance(this@PlaybackService).libraryDao()
                val last = dao.getTrackById(tail)
                val next = last?.let { LibraryDatabase.getInstance(this@PlaybackService).collectionDao().queue(filter, it, after = true, limit = 100).map { it.asMediaItem(this@PlaybackService) } }.orEmpty()
                withContext(Dispatchers.Main) {
                    if (queueRevision == revision) {
                        catalogEnded = next.isEmpty()
                        val discard = (player.currentMediaItemIndex - 50).coerceAtLeast(0)
                        if (discard > 0) player.removeMediaItems(0, discard)
                        if (next.isNotEmpty()) player.addMediaItems(next)
                    }
                }
            } finally { withContext(Dispatchers.Main) { appending = false } }
        }
    }

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        val job = io.launch { try { result.set(block()) } catch (failure: Exception) { result.setException(failure) } }
        result.addListener({ if (result.isCancelled) job.cancel() }, java.util.concurrent.Executor { it.run() })
        return result
    }

    private inner class SessionCallback : MediaLibrarySession.Callback {
        override fun onConnectAsync(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.ConnectionResult> =
            Futures.immediateFuture(if (controller.isTrusted || controller.uid == applicationInfo.uid)
                MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                    .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                    .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                        .add(PlaybackCommands.command(PlaybackCommands.PLAY_SELECTION))
                        .add(PlaybackCommands.command(PlaybackCommands.SLEEP))
                        .add(PlaybackCommands.command(PlaybackCommands.DSD_LEVEL))
                        .add(PlaybackCommands.command(PlaybackCommands.DSP)).build()).build()
            else MediaSession.ConnectionResult.reject())

        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
            queueRevision++; catalogEnded = false
            selection = null; PlaybackMemory.preferences(this@PlaybackService).edit().remove("selection").apply()
            return future {
                require(mediaItems.size <= PlaybackMemory.MAX_QUEUE) { "Hàng đợi cần chia theo cửa sổ." }
                val dao = LibraryDatabase.getInstance(this@PlaybackService).libraryDao()
                mediaItems.map { request ->
                    val id = request.mediaId.toLongOrNull()?.takeIf { it > 0 } ?: error("ID không hợp lệ")
                    (dao.getTrackById(id) ?: error("Bài hát không còn trong thư viện")).asMediaItem(this@PlaybackService)
                }
            }
        }
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                PlaybackCommands.PLAY_SELECTION -> {
                    val filter = TrackFilter.decode(args.getString("filter"))
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    val anchorId = args.getLong("anchor", 0)
                    val revision = ++queueRevision
                    future {
                        val database = LibraryDatabase.getInstance(this@PlaybackService)
                        val anchor = if (anchorId > 0) database.libraryDao().getTrackById(anchorId) else null
                        val items = database.collectionDao().queue(filter, anchor).map { it.asMediaItem(this@PlaybackService) }
                        withContext(Dispatchers.Main) {
                            if (queueRevision == revision && items.isNotEmpty()) {
                                selection = filter; catalogEnded = false
                                PlaybackMemory.preferences(this@PlaybackService).edit().putString("selection", filter.encode()).apply()
                                player.shuffleModeEnabled = false // SQL keyset shuffles across the entire selection, not just the loaded window.
                                player.setMediaItems(items, 0, 0); player.prepare(); player.play()
                            }
                        }
                        SessionResult(if (items.isEmpty()) SessionError.ERROR_BAD_VALUE else SessionResult.RESULT_SUCCESS)
                    }
                }
                PlaybackCommands.DSP -> {
                    val raw=args.getString("config")
                    if(raw==null || raw.length>4096) Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE)) else {
                        val config=DspSettings.decode(raw);val previous=dsp.config.getAndSet(config)
                        DspSettings.write(this@PlaybackService,config)
                        if(previous.sourceDirect!=config.sourceDirect) rebuildForDspRoute()
                        log.record("dsp_config","sourceDirect=${config.sourceDirect}; replayGain=${config.replayGain}; dither=${config.dither}")
                        updateSessionExtras();Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                }
                PlaybackCommands.DSD_LEVEL -> {
                    val gain = args.getInt("gainDb", -1)
                    if (gain !in DsdLevelState.ALLOWED_GAINS_DB) Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE)) else {
                        dsdLevel.setGainDb(gain)
                        DsdLevelSettings.write(this@PlaybackService, gain)
                        log.record("dsd_level", "gainDb=$gain; appliesTo=next_decoded_DSD_blocks; buffered_audio_may_retain_previous_gain")
                        updateSessionExtras()
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                }
                PlaybackCommands.SLEEP -> {
                    val minutes = args.getInt("minutes", 0)
                    if (minutes !in 0..240) Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE)) else {
                        mainHandler.removeCallbacks(sleepTick)
                        sleepAt = if (minutes == 0) 0 else SystemClock.elapsedRealtime() + minutes * 60_000L
                        if (minutes > 0) mainHandler.postDelayed(sleepTick, minutes * 60_000L)
                        log.record("sleep_timer_set", "minutes=$minutes"); updateSessionExtras()
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                }
                else -> super.onCustomCommand(session, controller, customCommand, args)
            }
        }
        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future { readMemory() }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = future {
            if (mediaId == ROOT_ID) LibraryResult.ofItem(rootItem(), null) else {
                val track = mediaId.toLongOrNull()?.let { LibraryDatabase.getInstance(this@PlaybackService).libraryDao().getTrackById(it) }
                if (track == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) else LibraryResult.ofItem(track.asMediaItem(this@PlaybackService), null)
            }
        }
        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            if (parentId != ROOT_ID || page < 0 || pageSize !in 1..200) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            else LibraryResult.ofItemList(LibraryDatabase.getInstance(this@PlaybackService).libraryDao().catalogPage(pageSize, page.toLong() * pageSize).map { it.asMediaItem(this@PlaybackService) }, params)
        }
    }
    private fun updateSessionExtras() {
        session?.setSessionExtras(Bundle().apply {
            putLong("sleepAt",sleepAt)
            if(::dsp.isInitialized) {
                putString("dsp",DspSettings.encode(dsp.config.get()));putDouble("gainDb",dsp.appliedGainDb);putLong("clipped",dsp.clippedSamples)
            }
            if(::dsdLevel.isInitialized) {
                putInt("dsdGainDb",dsdLevel.gainDb)
                putLong("dsdOverFullScaleSamples",dsdLevel.overFullScaleSamples)
            }
            putString("source",sourceSignal?.let { format -> (format.customData as? DsdHeader)?.let { "DSD${it.sampleRate/44100} · ${it.sampleRate} Hz · ${it.channels} kênh · 1bit" } ?: describeFormat(format) } ?: "Chưa có dữ liệu từ renderer")
            putString("decoded",decodedSignal?.let(::describeFormat) ?: "Chưa có PCM sau giải mã")
            putString("decoder",decoderName)
            putString("output",outputSignal?.let { "${pcmName(it.encoding)} · ${it.sampleRate} Hz · ${Integer.bitCount(it.channelConfig)} kênh · offload=${it.offload}" } ?: "AudioTrack chưa được tạo")
        })
    }

    private fun observeCurrentMetadata() {
        val id = player.currentMediaItem?.mediaId?.toLongOrNull()
        if (id == watchedId) return
        watchedId = id; metadataWatch?.cancel()
        if (id == null) return
        metadataWatch = io.launch {
            LibraryDatabase.getInstance(this@PlaybackService).libraryDao().observeTrack(id).distinctUntilChanged().collect { track ->
                if (track != null) {
                    dsp.liveTags.set(track.id.toString() to ReplayGainInfo(track.trackGain,track.albumGain,track.trackPeak,track.albumPeak))
                    val item = track.asMediaItem(this@PlaybackService)
                    withContext(Dispatchers.Main) {
                        if (player.currentMediaItem?.mediaId == id.toString()) {
                            val current = player.currentMediaItem!!
                            if (current.mediaMetadata != item.mediaMetadata)
                                player.replaceMediaItem(player.currentMediaItemIndex, current.buildUpon().setMediaMetadata(item.mediaMetadata).build())
                        }
                    }
                }
            }
        }
    }
    private fun describeFormat(format:Format):String = "${format.sampleMimeType ?: "Chưa rõ định dạng"} · ${pcmName(format.pcmEncoding)} · ${format.sampleRate.takeIf { it>0 } ?: "?"} Hz · ${format.channelCount.takeIf { it>0 } ?: "?"} kênh"
    private fun pcmName(encoding:Int):String = when(encoding) {
        C.ENCODING_PCM_16BIT -> "PCM16"; C.ENCODING_PCM_24BIT -> "PCM24";C.ENCODING_PCM_32BIT -> "PCM32";C.ENCODING_PCM_FLOAT -> "PCM float32";C.ENCODING_PCM_8BIT -> "PCM8";else -> "Chưa có bit depth PCM"
    }
    private fun rootItem() = MediaItem.Builder().setMediaId(ROOT_ID).setMediaMetadata(MediaMetadata.Builder()
        .setTitle("Thư viện nhạc").setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build()
    companion object { const val ROOT_ID = "library" }
}
