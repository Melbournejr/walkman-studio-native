// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.walkmanstudio.nativeplayer.core.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

@androidx.annotation.OptIn(UnstableApi::class)
class DspAudioProcessor(private val state: DspState, private val quantizer: TpdfQuantizer = TpdfQuantizer(), private val floatOutput: Boolean = false): BaseAudioProcessor() {
    private var engine: DspEngine?=null
    private var lastConfig: DspConfig?=null
    private var lastTags=ReplayGainInfo()
    private var streamTags=ReplayGainInfo()
    private var nextChannel=0
    private val partialFrame = FloatArray(32)
    private var partialSample = 0
    private var partialBytes = 0
    private var mediaId: String?=null
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (state.config.get().sourceDirect && !floatOutput) return AudioProcessor.AudioFormat.NOT_SET
        if(sampleBytes(inputAudioFormat.encoding) == 0 || (!floatOutput && inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) || inputAudioFormat.channelCount !in 1..32 || inputAudioFormat.sampleRate !in 8000..768000)
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return if (floatOutput) AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate, inputAudioFormat.channelCount, C.ENCODING_PCM_FLOAT
        ) else inputAudioFormat
    }
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        partialBytes=0;partialSample=0;nextChannel=0;mediaId=null;streamTags=ReplayGainInfo()
        val uid=streamMetadata.periodUid
        if(uid!=null && !streamMetadata.timeline.isEmpty) {
            val period=streamMetadata.timeline.getPeriodByUid(uid,Timeline.Period())
            val window=streamMetadata.timeline.getWindow(period.windowIndex,Timeline.Window())
            mediaId=window.mediaItem.mediaId;streamTags=window.mediaItem.mediaMetadata.extras.replayGainInfo()
        }
        lastConfig=null;engine=null
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        val config=state.config.get()
        if (config.sourceDirect && !floatOutput) {
            val output = replaceOutputBuffer(inputBuffer.remaining())
            output.put(inputBuffer).flip()
            return
        }
        val encoding = inputAudioFormat.encoding
        val bytes = sampleBytes(encoding)
        val samples = (inputBuffer.remaining() + partialBytes) / bytes
        val outputBytes = if (floatOutput) (samples + nextChannel) * 4 else samples * 2
        val output = replaceOutputBuffer(outputBytes).order(ByteOrder.LITTLE_ENDIAN)
        if (floatOutput) for (channel in 0 until nextChannel) output.putFloat(partialFrame[channel])
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val live=state.liveTags.get();val tags=live?.takeIf { it.first==mediaId }?.second ?: streamTags
        if(engine==null) { engine=DspEngine(inputAudioFormat.sampleRate,inputAudioFormat.channelCount,config,tags);lastConfig=config;lastTags=tags }
        else if(lastConfig!=config || lastTags!=tags) {
            // Dither belongs to the final quantizer. Toggling it must not restart an EQ/gain ramp.
            if(lastConfig?.copy(dither=config.dither)!=config || lastTags!=tags) engine!!.update(config,tags)
            lastConfig=config;lastTags=tags
        }
        val dsp=engine!!;var channel=nextChannel
        while (inputBuffer.hasRemaining()) {
            val raw: Int
            if (partialBytes == 0 && inputBuffer.remaining() >= bytes) {
                raw = when (bytes) {
                    2 -> inputBuffer.short.toInt()
                    3 -> (inputBuffer.get().toInt() and 255) or
                        ((inputBuffer.get().toInt() and 255) shl 8) or (inputBuffer.get().toInt() shl 16)
                    else -> inputBuffer.int
                }
            } else {
                while (partialBytes < bytes && inputBuffer.hasRemaining()) {
                    partialSample = partialSample or ((inputBuffer.get().toInt() and 255) shl (8 * partialBytes++))
                }
                if (partialBytes < bytes) break
                val shift = 32 - bytes * 8
                raw = (partialSample shl shift) shr shift
                partialSample = 0
                partialBytes = 0
            }
            val sample = when (encoding) {
                C.ENCODING_PCM_16BIT -> raw / 32768.0
                C.ENCODING_PCM_24BIT -> raw / 8388608.0
                C.ENCODING_PCM_32BIT -> raw / 2147483648.0
                else -> Float.fromBits(raw).toDouble()
            }
            val processed = dsp.process(sample, channel)
            if (floatOutput) output.putFloat(processed.toFloat())
            else output.putShort(quantizer.quantize(processed, config.dither && dsp.needsDither))
            channel++
            if (channel == inputAudioFormat.channelCount) channel = 0
        }
        if (floatOutput && channel > 0) {
            val frameStart = output.position() - channel * 4
            for (i in 0 until channel) partialFrame[i] = output.getFloat(frameStart + i * 4)
            output.position(frameStart)
        }
        nextChannel=channel
        state.appliedGainDb=dsp.appliedGainDb;state.clippedSamples=dsp.clippedSamples
        output.flip()
    }
    override fun onQueueEndOfStream() {
        partialBytes = 0
        partialSample = 0
    }

    private fun sampleBytes(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }

    override fun onReset() { engine=null;lastConfig=null;mediaId=null }
}
