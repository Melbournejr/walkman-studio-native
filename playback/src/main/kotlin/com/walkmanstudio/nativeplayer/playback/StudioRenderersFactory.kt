// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.*

@androidx.annotation.OptIn(UnstableApi::class)
class StudioRenderersFactory(context:Context,private val state:DspState,private val decoded:(Format)->Unit): DefaultRenderersFactory(context) {
    private val direct=state.config.get().sourceDirect
    init { setEnableAudioFloatOutput(direct) }
    override fun buildAudioSink(context:Context,enableFloatOutput:Boolean,enableAudioOutputPlaybackParams:Boolean):AudioSink {
        val sink=DefaultAudioSink.Builder(context).setEnableFloatOutput(direct)
            .setAudioProcessors(processors(state)).build()
        return object:ForwardingAudioSink(sink) {
            // Compressed passthrough/offload cannot run an AudioProcessor.
            override fun getFormatSupport(format:Format):Int = if(!direct && format.sampleMimeType!=MimeTypes.AUDIO_RAW) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)
            override fun supportsFormat(format:Format):Boolean = getFormatSupport(format)!=AudioSink.SINK_FORMAT_UNSUPPORTED
            override fun getFormatOffloadSupport(format:Format):AudioOffloadSupport = if(!direct) AudioOffloadSupport.DEFAULT_UNSUPPORTED else super.getFormatOffloadSupport(format)
            override fun configure(audioSinkConfig:AudioSink.AudioSinkConfig) { super.configure(audioSinkConfig);decoded(audioSinkConfig.format) }
        }
    }
    companion object {
        fun processors(state:DspState):Array<AudioProcessor> = if(state.config.get().sourceDirect) emptyArray() else arrayOf(DspAudioProcessor(state))
    }
}
