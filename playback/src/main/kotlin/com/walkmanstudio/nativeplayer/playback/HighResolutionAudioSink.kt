// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * Media3 1.11's float path omits the custom AudioProcessorChain. Process high-resolution
 * decoder PCM before the sink so it can still handle mapping, gapless trimming and timing.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class HighResolutionAudioSink(
    sink: AudioSink,
    state: DspState,
    private val decoded: (Format) -> Unit,
) : ForwardingAudioSink(sink) {
    private val processor = DspAudioProcessor(state, floatOutput = true)
    private val pcm16Fallback = ToInt16PcmAudioProcessor()
    private var inputProcessor: AudioProcessor? = null
    private var converted: ByteBuffer? = null
    private var metadata = AudioProcessor.StreamMetadata.DEFAULT

    override fun getFormatSupport(format: Format): Int {
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW) return AudioSink.SINK_FORMAT_UNSUPPORTED
        if (isHighResolution(format)) {
            val encoding = outputEncoding(format) ?: return AudioSink.SINK_FORMAT_UNSUPPORTED
            return if (format.pcmEncoding == encoding) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
            else AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
        }
        return super.getFormatSupport(format)
    }

    override fun supportsFormat(format: Format) = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.DEFAULT_UNSUPPORTED

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val format = audioSinkConfig.format
        val encoding = if (isHighResolution(format)) outputEncoding(format)
            ?: throw AudioSink.ConfigurationException("No supported PCM output", format)
        else null
        val selected = when (encoding) {
            C.ENCODING_PCM_FLOAT -> processor
            C.ENCODING_PCM_16BIT -> pcm16Fallback
            else -> null
        }
        if (selected != null) {
            try {
                selected.configure(AudioProcessor.AudioFormat(format))
            } catch (error: AudioProcessor.UnhandledAudioFormatException) {
                throw AudioSink.ConfigurationException(error, format)
            }
            metadata = AudioProcessor.StreamMetadata.Builder()
                .setTimeline(audioSinkConfig.timeline)
                .setPeriodUid(audioSinkConfig.mediaPeriodId?.periodUid)
                .build()
            selected.flush(metadata)
        }
        inputProcessor = selected
        decoded(format)
        val sinkConfig = if (encoding != null) AudioSink.AudioSinkConfig.Builder(withEncoding(format, encoding))
            .setTimeline(audioSinkConfig.timeline)
            .setMediaPeriodId(audioSinkConfig.mediaPeriodId)
            .setOutputChannelMapping(audioSinkConfig.outputChannelMapping)
            .setPreferredBufferSizeOverride(audioSinkConfig.preferredBufferSizeOverride)
            .build() else audioSinkConfig
        super.configure(sinkConfig)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val active = inputProcessor ?: return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val pending = converted
        val output = pending ?: run {
            // Media3 retries with the same buffer until the sink accepts it in full.
            val start = buffer.position()
            active.queueInput(buffer)
            buffer.position(start)
            active.output.also { converted = it }
        }
        if ((pending != null || output.hasRemaining()) &&
            !super.handleBuffer(output, presentationTimeUs, encodedAccessUnitCount)) return false
        converted = null
        buffer.position(buffer.limit())
        return true
    }

    override fun flush() {
        converted = null
        inputProcessor?.flush(metadata)
        super.flush()
    }

    override fun handleDiscontinuity() {
        if (converted == null) inputProcessor?.flush(metadata)
        super.handleDiscontinuity()
    }

    override fun reset() {
        converted = null
        inputProcessor = null
        pcm16Fallback.reset()
        processor.reset()
        metadata = AudioProcessor.StreamMetadata.DEFAULT
        super.reset()
    }

    private fun isHighResolution(format: Format) = when (format.pcmEncoding) {
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> true
        else -> false
    }

    private fun outputEncoding(format: Format): Int? {
        if (super.getFormatSupport(withEncoding(format, C.ENCODING_PCM_FLOAT)) == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY) {
            return C.ENCODING_PCM_FLOAT
        }
        // Keep the existing PCM16 DSP chain available on output paths that reject float.
        return if (super.getFormatSupport(withEncoding(format, C.ENCODING_PCM_16BIT)) == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY)
            C.ENCODING_PCM_16BIT else null
    }

    private fun withEncoding(format: Format, encoding: Int): Format =
        format.buildUpon().setPcmEncoding(encoding).build()
}
