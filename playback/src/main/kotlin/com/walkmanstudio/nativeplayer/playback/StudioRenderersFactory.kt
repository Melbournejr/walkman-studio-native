// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.*

@androidx.annotation.OptIn(UnstableApi::class)
class StudioRenderersFactory(context:Context,private val state:DspState,private val decoded:(Format)->Unit,
    private val observedTrack: (android.media.AudioTrack) -> Unit = {},
    private val releasedTrack: (android.media.AudioTrack) -> Unit = {},
    private val observedVolume: (android.media.AudioTrack, Float) -> Unit = { _, _ -> },
): DefaultRenderersFactory(context) {
    private val direct=state.config.get().sourceDirect
    init { setEnableAudioFloatOutput(true) }
    override fun buildAudioSink(context:Context,enableFloatOutput:Boolean,enableAudioOutputPlaybackParams:Boolean):AudioSink {
        val provider = object : ForwardingAudioOutputProvider(AudioTrackAudioOutputProvider.Builder(context).build()) {
            override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
                val output = super.getAudioOutput(config)
                if (output !is AudioTrackAudioOutput) return output
                val track = output.audioTrack
                observedTrack(track)
                return object : ForwardingAudioOutput(output) {
                    override fun setVolume(volume: Float) { super.setVolume(volume); observedVolume(track, volume) }
                    override fun release() { releasedTrack(track); super.release() }
                }
            }
        }
        val sink=DefaultAudioSink.Builder(context).setAudioOutputProvider(provider).setEnableFloatOutput(true)
            .setAudioProcessors(processors(state)).build()
        if (!direct) return HighResolutionAudioSink(sink, state, decoded)
        return object:ForwardingAudioSink(sink) {
            override fun configure(audioSinkConfig:AudioSink.AudioSinkConfig) { decoded(audioSinkConfig.format);super.configure(audioSinkConfig) }
        }
    }
    companion object {
        fun processors(state:DspState):Array<AudioProcessor> = if(state.config.get().sourceDirect) emptyArray() else arrayOf(DspAudioProcessor(state))
    }
}
