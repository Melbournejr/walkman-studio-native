// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.walkmanstudio.nativeplayer.core.*
import java.nio.ByteBuffer

@androidx.annotation.OptIn(UnstableApi::class)
class DspAudioProcessor(private val state: DspState, private val quantizer: TpdfQuantizer = TpdfQuantizer()): BaseAudioProcessor() {
    private var engine: DspEngine?=null
    private var lastConfig: DspConfig?=null
    private var lastTags=ReplayGainInfo()
    private var streamTags=ReplayGainInfo()
    private var nextChannel=0
    private var mediaId: String?=null
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (state.config.get().sourceDirect) return AudioProcessor.AudioFormat.NOT_SET
        if(inputAudioFormat.encoding!=C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..32 || inputAudioFormat.sampleRate !in 8000..768000)
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        nextChannel=0;mediaId=null;streamTags=ReplayGainInfo()
        val uid=streamMetadata.periodUid
        if(uid!=null && !streamMetadata.timeline.isEmpty) {
            val period=streamMetadata.timeline.getPeriodByUid(uid,Timeline.Period())
            val window=streamMetadata.timeline.getWindow(period.windowIndex,Timeline.Window())
            mediaId=window.mediaItem.mediaId;streamTags=window.mediaItem.mediaMetadata.extras.replayGainInfo()
        }
        lastConfig=null;engine=null
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        val output=replaceOutputBuffer(inputBuffer.remaining())
        val config=state.config.get()
        if(config.sourceDirect) { output.put(inputBuffer);output.flip();return }
        val live=state.liveTags.get();val tags=live?.takeIf { it.first==mediaId }?.second ?: streamTags
        if(engine==null) { engine=DspEngine(inputAudioFormat.sampleRate,inputAudioFormat.channelCount,config,tags);lastConfig=config;lastTags=tags }
        else if(lastConfig!=config || lastTags!=tags) {
            // Dither belongs to the final quantizer. Toggling it must not restart an EQ/gain ramp.
            if(lastConfig?.copy(dither=config.dither)!=config || lastTags!=tags) engine!!.update(config,tags)
            lastConfig=config;lastTags=tags
        }
        val dsp=engine!!;var channel=nextChannel
        while(inputBuffer.remaining()>=2) {
            val sample=inputBuffer.short.toDouble()/32768.0
            val processed=dsp.process(sample,channel)
            output.putShort(quantizer.quantize(processed,config.dither))
            channel++;if(channel==inputAudioFormat.channelCount)channel=0
        }
        check(!inputBuffer.hasRemaining()) { "PCM input must contain whole samples" }
        nextChannel=channel
        state.appliedGainDb=dsp.appliedGainDb;state.clippedSamples=dsp.clippedSamples
        output.flip()
    }
    override fun onReset() { engine=null;lastConfig=null;mediaId=null }
}
