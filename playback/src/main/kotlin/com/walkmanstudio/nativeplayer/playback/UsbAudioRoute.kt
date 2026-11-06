// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback

import android.content.Context
import android.media.*
import android.os.Build
import android.os.Handler
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import com.walkmanstudio.nativeplayer.core.*
import org.json.JSONArray
import org.json.JSONObject

internal fun isUsb(type: Int) = type == AudioDeviceInfo.TYPE_USB_DEVICE ||
    type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_ACCESSORY

internal fun pcmKind(encoding: Int): PcmKind? = when (encoding) {
    AudioFormat.ENCODING_PCM_16BIT -> PcmKind.PCM16
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> PcmKind.PCM24
    AudioFormat.ENCODING_PCM_32BIT -> PcmKind.PCM32
    AudioFormat.ENCODING_PCM_FLOAT -> PcmKind.FLOAT
    else -> null
}

@androidx.annotation.OptIn(UnstableApi::class)
internal object OutputTelemetry {
    fun sourceDepth(format: Format?): Int? {
        if (format == null) return null
        if (format.customData is DsdHeader) return 1
        when (format.pcmEncoding) {
            C.ENCODING_PCM_8BIT -> return 8
            C.ENCODING_PCM_16BIT -> return 16
            C.ENCODING_PCM_24BIT -> return 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> return 32
        }
        // FLAC initialization data contains fLaC + STREAMINFO (RFC 9639 §8.2).
        if (format.sampleMimeType == "audio/flac") {
            val data = format.initializationData.firstOrNull() ?: return null
            if (data.size >= 42 && data[0] == 0x66.toByte() && data[1] == 0x4c.toByte() &&
                data[2] == 0x61.toByte() && data[3] == 0x43.toByte() && data[4].toInt() and 0x7f == 0) {
                return (((data[20].toInt() and 1) shl 4) or ((data[21].toInt() and 0xf0) ushr 4)) + 1
            }
        }
        return null
    }
    fun decoder(trackId: String?, format: Format?, sourceDepth: Int?): JSONObject = JSONObject()
        .put("trackId", trackId ?: JSONObject.NULL)
        .put("sampleMimeType", format?.sampleMimeType ?: JSONObject.NULL)
        .put("pcmEncoding", format?.pcmEncoding ?: JSONObject.NULL)
        .put("sampleRate", format?.sampleRate ?: JSONObject.NULL)
        .put("sourceBitDepth", sourceDepth ?: JSONObject.NULL)
        .put("observation", if (format == null) "awaiting_decoder" else "decoder_sink_input_before_sink_conversion")

    fun device(device: AudioDeviceInfo): JSONObject = JSONObject().put("id", device.id)
        .put("type", device.type).put("productName", device.productName.toString())
        .put("sampleRates", JSONArray(device.sampleRates.toList()))
        .put("encodings", JSONArray(device.encodings.toList()))
        .put("channelCounts", JSONArray(device.channelCounts.toList()))
}

/** Observes public Media3 AudioTrackAudioOutput, never private fields or requested-only config. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class UsbAudioRoute(
    private val context: Context,
    private val handler: Handler,
    private val log: LifecycleLog,
    private val direct: () -> Boolean,
    private val dsdGain: () -> Int,
    private val changed: () -> Unit,
) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val preferences = context.getSharedPreferences("usb-output", Context.MODE_PRIVATE)
    private val api = if (Build.VERSION.SDK_INT >= 34) Api34MixerAccess(manager, log) else null
    private val session = api?.let(::UsbMixerSession)
    private var track: AudioTrack? = null
    private var trackVolume = 1f
    private var trackBypassesDsp = false
    private var decoded: DecodedPcm? = null
    private var decodedFormat: Format? = null
    private var inputFormat: Format? = null
    private var trackId: String? = null
    private var closed = false
    private var requested = preferences.getBoolean("enabled", false)
    // Gain changes affect queued DSD blocks too; never confirm until a fresh zero-gain track.
    private var dsdBufferedGain = false
    @Volatile var usbConnected = false
        private set
    @Volatile var status = UsbMixerStatus(false, requested, false, "Chưa đo đường ra")
        private set
    private val routing = AudioRouting.OnRoutingChangedListener { refresh("routed_device_changed", true) }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh("devices_added", true)
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh("devices_removed", true)
    }
    init {
        manager.registerAudioDeviceCallback(devices, handler)
        if (Build.VERSION.SDK_INT >= 34) api?.listen(context.mainExecutor) { refresh("preferred_mixer_changed", true) }
        refresh("service_created", true)
    }
    @Synchronized fun setEnabled(enabled: Boolean, warningAccepted: Boolean): Boolean {
        if (enabled && (!status.available || (!warningAccepted && !preferences.getBoolean("warningAccepted", false)))) return false
        requested = enabled
        preferences.edit().putBoolean("enabled", enabled).apply()
        if (warningAccepted) preferences.edit().putBoolean("warningAccepted", true).apply()
        refresh("user_switch", true)
        return true
    }
    fun warningAccepted() = preferences.getBoolean("warningAccepted", false)
    @Synchronized fun transition(id: String?) {
        trackId = id; decoded = null; decodedFormat = null; inputFormat = null
        log.record("decoder_output", OutputTelemetry.decoder(id, null, null))
        refresh("media_transition", true)
    }
    @Synchronized fun input(format: Format) {
        inputFormat = format
        decoded = decoded?.copy(sourceDepth = OutputTelemetry.sourceDepth(format))
        if (decodedFormat != null) logDecoder()
        refresh("source_format", false)
    }
    @Synchronized fun decoder(format: Format) {
        decodedFormat = format
        decoded = pcmKind(format.pcmEncoding)?.let { DecodedPcm(PcmFormat(format.sampleRate, it, format.channelCount),
            OutputTelemetry.sourceDepth(inputFormat), format.customData is DsdHeader) }
        logDecoder()
        refresh("decoder_configured", false)
    }
    private fun logDecoder() = log.record("decoder_output", OutputTelemetry.decoder(trackId, decodedFormat, OutputTelemetry.sourceDepth(inputFormat)))
    @Synchronized fun attached(audioTrack: AudioTrack, bypassesDsp: Boolean = direct()) {
        if (closed) return
        track?.removeOnRoutingChangedListener(routing)
        track = audioTrack; trackVolume = 1f; trackBypassesDsp = bypassesDsp; dsdBufferedGain = dsdGain() != 0
        audioTrack.addOnRoutingChangedListener(routing, handler)
        refresh("audio_track_created", true)
    }
    @Synchronized fun released(audioTrack: AudioTrack) {
        audioTrack.removeOnRoutingChangedListener(routing)
        if (track === audioTrack) { track = null; refresh("audio_track_released", true) }
    }
    @Synchronized fun volume(audioTrack: AudioTrack, value: Float) {
        if (track === audioTrack) { trackVolume = value; refresh("track_volume", false) }
    }
    @Synchronized fun refresh(reason: String, writeLog: Boolean = false) {
        if (closed) return
        if (dsdGain() != 0) dsdBufferedGain = true
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        usbConnected = outputs.any { isUsb(it.type) }
        val routed = track?.routedDevice
        val actual = track?.let { audio -> pcmKind(audio.audioFormat)?.let { kind ->
            ActualPcm(PcmFormat(audio.sampleRate, kind, audio.channelCount), routed?.id, routed?.let { isUsb(it.type) } == true, trackVolume, trackBypassesDsp)
        } }
        val before = status
        status = session?.evaluate(Build.VERSION.SDK_INT, requested, outputs.map { UsbEndpoint(it.id, isUsb(it.type)) },
            decoded, actual, direct(), if (dsdBufferedGain && decoded?.fromDsd == true) 1 else dsdGain())
            ?: UsbMixerStatus(false, requested, false, "Cần Android 14 trở lên")
        if (writeLog || before != status) {
            val event = JSONObject().put("reason", reason).put("apiLevel", Build.VERSION.SDK_INT)
                .put("devices", JSONArray(outputs.map { device -> OutputTelemetry.device(device).apply {
                    if (Build.VERSION.SDK_INT >= 34) api?.describe(device, this)
                } }))
                .put("routedDevice", routed?.let(OutputTelemetry::device) ?: JSONObject.NULL)
                .put("audioTrack", track?.let { audio -> JSONObject().put("sampleRate", audio.sampleRate)
                    .put("encoding", audio.audioFormat).put("channelCount", audio.channelCount)
                    .put("performanceMode", audio.performanceMode).put("audioSessionId", audio.audioSessionId)
                    .put("volume", trackVolume).put("bypassesDsp", trackBypassesDsp) } ?: JSONObject.NULL)
                .put("requested", requested).put("confirmed", status.verified).put("statusReason", status.reason)
            log.record("output_route", event)
        }
        if (before != status) handler.post { changed() }
    }
    @Synchronized fun close() {
        session?.release()
        track?.removeOnRoutingChangedListener(routing); track = null
        manager.unregisterAudioDeviceCallback(devices)
        if (Build.VERSION.SDK_INT >= 34) api?.close()
        closed = true
        log.record("output_route", JSONObject().put("reason", "service_stopped_cleanup").put("apiLevel", Build.VERSION.SDK_INT))
    }
    @Synchronized fun outputDescription(): String? = track?.let {
        val name = when (pcmKind(it.audioFormat)) {
            PcmKind.PCM16 -> "PCM16"; PcmKind.PCM24 -> "PCM24"; PcmKind.PCM32 -> "PCM32"
            PcmKind.FLOAT -> "PCM float32"; null -> return@let null
        }
        "$name · ${it.sampleRate} Hz · ${it.channelCount} kênh · offload=${Build.VERSION.SDK_INT >= 29 && it.isOffloadedPlayback}"
    }
}

@RequiresApi(34)
private class Api34MixerAccess(private val manager: AudioManager, private val log: LifecycleLog) : MixerAccess {
    // Must equal the attributes supplied to ExoPlayer; Android currently supports USAGE_MEDIA only.
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private var listener: AudioManager.OnPreferredMixerAttributesChangedListener? = null
    private val known = mutableMapOf<Int, AudioDeviceInfo>()
    private fun device(id: Int): AudioDeviceInfo? = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .firstOrNull { it.id == id }?.also { known[id] = it } ?: known[id]
    private fun model(value: AudioMixerAttributes): MixerOption? = pcmKind(value.format.encoding)?.let {
        MixerOption(PcmFormat(value.format.sampleRate, it, value.format.channelCount),
            value.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
    }
    private fun query(id: Int) = device(id)?.let(manager::getSupportedMixerAttributes).orEmpty()
    override fun supported(deviceId: Int) = try { query(deviceId).mapNotNull(::model) }
        catch (error: RuntimeException) { failed("supported", error); emptyList() }
    override fun preferred(deviceId: Int): MixerOption? = try {
        device(deviceId)?.let { manager.getPreferredMixerAttributes(attributes, it) }?.let(::model)
    } catch (error: RuntimeException) { failed("preferred", error); null }
    override fun set(deviceId: Int, option: MixerOption): Boolean = try {
        val device = device(deviceId)
        val native = query(deviceId).firstOrNull { model(it) == option }
        device != null && native != null && manager.setPreferredMixerAttributes(attributes, device, native)
    } catch (error: RuntimeException) { failed("set", error); false }
    override fun clear(deviceId: Int): Boolean = try {
        device(deviceId)?.let { manager.clearPreferredMixerAttributes(attributes, it) } ?: true
    } catch (error: RuntimeException) { failed("clear", error); false }
    fun listen(executor: java.util.concurrent.Executor, changed: () -> Unit) {
        val callback = AudioManager.OnPreferredMixerAttributesChangedListener { attr, _, _ ->
            if (attr.usage == attributes.usage && attr.contentType == attributes.contentType) changed()
        }
        try { manager.addOnPreferredMixerAttributesChangedListener(executor, callback); listener = callback }
        catch (error: RuntimeException) { failed("listener", error) }
    }
    fun describe(device: AudioDeviceInfo, target: JSONObject) {
        known[device.id] = device
        try {
            val supported = manager.getSupportedMixerAttributes(device)
            target.put("supportedMixerAttributes", JSONArray(supported.map(::json)))
                .put("preferredMixerAttributes", manager.getPreferredMixerAttributes(attributes, device)?.let(::json) ?: JSONObject.NULL)
            if (supported.isEmpty()) target.put("mixerNote", "thiết bị/OEM không hỗ trợ mixer bit-perfect")
        } catch (error: RuntimeException) { failed("describe", error); target.put("mixerError", error.javaClass.simpleName) }
    }
    private fun json(value: AudioMixerAttributes) = JSONObject().put("encoding", value.format.encoding)
        .put("sampleRate", value.format.sampleRate).put("channelMask", value.format.channelMask)
        .put("channelCount", value.format.channelCount).put("behavior", value.mixerBehavior)
    private fun failed(operation: String, error: RuntimeException) = log.record("usb_mixer_error", "$operation: ${error.javaClass.simpleName}")
    fun close() {
        try { listener?.let(manager::removeOnPreferredMixerAttributesChangedListener) }
        catch (error: RuntimeException) { failed("remove_listener", error) }
        listener = null
    }
}
