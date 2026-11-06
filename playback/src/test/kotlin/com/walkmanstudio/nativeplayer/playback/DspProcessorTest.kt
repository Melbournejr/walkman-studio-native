// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback

import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import com.walkmanstudio.nativeplayer.core.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@androidx.annotation.OptIn(UnstableApi::class)
class DspProcessorTest {
    @Test fun sourceDirectRemovesEveryAppProcessorAndInactiveProcessorCopiesBits() {
        val state=DspState(DspConfig(true,List(10){12.0},12.0,12.0,ReplayGainMode.TRACK))
        assertTrue(StudioRenderersFactory.processors(state).isEmpty())
        val processor=DspAudioProcessor(state)
        assertEquals(AudioProcessor.AudioFormat.NOT_SET,processor.configure(AudioProcessor.AudioFormat(48000,2,C.ENCODING_PCM_16BIT)))
        assertFalse(processor.isActive);processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val samples=shortArrayOf(-32768,-1,0,1,16384,32767)
        assertArrayEquals(samples,run(processor,samples))
    }
    @Test fun flatPcm16IsExactAndBufferPartitionDoesNotMixChannels() {
        // This test verifies the unchanged DSP math/quantization with noise explicitly disabled.
        val state=DspState(DspConfig(false,preventClipping=false,dither=false));val flat=DspAudioProcessor(state)
        flat.configure(AudioProcessor.AudioFormat(48000,2,C.ENCODING_PCM_16BIT));flat.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val samples=ShortArray(2000){((it*577)%60000-30000).toShort()}
        assertArrayEquals(samples,run(flat,samples))
        val config=DspConfig(false,List(10){if(it==4)6.0 else 0.0},preventClipping=false,dither=false)
        fun make()=DspAudioProcessor(DspState(config)).also { it.configure(AudioProcessor.AudioFormat(48000,2,C.ENCODING_PCM_16BIT));it.flush(AudioProcessor.StreamMetadata.DEFAULT) }
        val one=run(make(),samples);val split=make();val parts=mutableListOf<Short>()
        samples.toList().chunked(113).forEach { parts.addAll(run(split,it.toShortArray()).toList()) }
        assertArrayEquals(one,parts.toShortArray())
    }
    @Test fun replayGainFollowsPcmStreamMetadataRatherThanUiTransitionTiming() {
        val state=DspState(DspConfig(false,replayGain=ReplayGainMode.TRACK,preventClipping=false,dither=false))
        val processor=DspAudioProcessor(state);processor.configure(AudioProcessor.AudioFormat(48000,2,C.ENCODING_PCM_16BIT))
        fun stream(id:String,gain:Double):AudioProcessor.StreamMetadata {
            val item=MediaItem.Builder().setMediaId(id).setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply{putDouble("trackGain",gain)}).build()).build()
            val timeline=SinglePeriodTimeline(1000000L,true,false,false,null,item)
            return AudioProcessor.StreamMetadata.Builder().setTimeline(timeline).setPeriodUid(timeline.getUidOfPeriod(0)).build()
        }
        processor.flush(stream("a",-6.0));state.liveTags.set("wrong-track" to ReplayGainInfo(12.0))
        val first=run(processor,ShortArray(200){10000});assertEquals(5012.0,first[100].toDouble(),1.0)
        processor.flush(stream("b",0.0));assertArrayEquals(ShortArray(200){10000},run(processor,ShortArray(200){10000}))
    }
    @Test fun savedConfigRoundTripsAndMalformedJsonCannotIntroduceNanOrExtraBands() {
        val config=DspConfig(false,List(10){it-5.0},8.0,-6.0,ReplayGainMode.ALBUM,false)
        assertEquals(config,DspSettings.decode(DspSettings.encode(config)))
        assertEquals(DspConfig(),DspSettings.decode("invalid"));assertEquals(DspConfig(),DspSettings.decode("{}"))
        assertEquals(DspConfig(),DspSettings.decode(org.json.JSONObject(DspSettings.encode(config)).put("bass",1000).toString()))
    }
    @Test fun nonPcm16CannotSilentlyBypassDsp() {
        val processor=DspAudioProcessor(DspState(DspConfig(false)))
        try{processor.configure(AudioProcessor.AudioFormat(48000,2,C.ENCODING_PCM_FLOAT));fail("Sink must convert before processor")}catch(_:AudioProcessor.UnhandledAudioFormatException){}
    }
    @Test fun ditherDefaultsOnAndLegacyPreferencesMigrateWithoutChangingEqOrReplayGain() {
        assertTrue(DspConfig().dither)
        val config=DspConfig(false,List(10){it-5.0},8.0,-6.0,ReplayGainMode.ALBUM,false,dither=false)
        assertEquals(config,DspSettings.decode(DspSettings.encode(config)))
        val legacy=org.json.JSONObject(DspSettings.encode(config)).apply { remove("dither") }.toString()
        assertEquals(config.copy(dither=true),DspSettings.decode(legacy))
    }
    @Test fun enabledDitherSurvivesOddStereoBufferPartitionsWithoutChangingSequence() {
        val config=DspConfig(false,List(10){if(it==4)3.0 else 0.0},preventClipping=false,dither=true)
        fun make()=DspAudioProcessor(DspState(config),TpdfQuantizer(Random(8121))).also {
            it.configure(AudioProcessor.AudioFormat(48000,2,C.ENCODING_PCM_16BIT));it.flush(AudioProcessor.StreamMetadata.DEFAULT)
        }
        val samples=ShortArray(20000){((it*577)%24000-12000).toShort()}
        val one=run(make(),samples);val split=make();val parts=mutableListOf<Short>()
        samples.toList().chunked(113).forEach { parts.addAll(run(split,it.toShortArray()).toList()) }
        assertArrayEquals(one,parts.toShortArray())
    }
    @Test fun togglingDitherChangesOnlyDspQuantizationAndSourceDirectRemainsBitExact() {
        val state=DspState(DspConfig(false,preventClipping=false,dither=false))
        val processor=DspAudioProcessor(state,TpdfQuantizer(Random(8122)))
        processor.configure(AudioProcessor.AudioFormat(48000,2,C.ENCODING_PCM_16BIT));processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val silence=ShortArray(10000)
        assertArrayEquals(silence,run(processor,silence))
        state.config.set(state.config.get().copy(dither=true))
        assertTrue(run(processor,silence).any {it.toInt()!=0})
        state.config.set(state.config.get().copy(sourceDirect=true))
        assertTrue(StudioRenderersFactory.processors(state).isEmpty())
        val samples=ShortArray(10000){((it*379)%65536-32768).toShort()}
        assertArrayEquals(samples,run(processor,samples))
        state.config.set(state.config.get().copy(dither=false))
        assertArrayEquals(samples,run(processor,samples))
    }
    @Test fun ditherOnlyChangesDoNotRestartAnInFlightEqCoefficientRamp() {
        fun make():Pair<DspState,DspAudioProcessor> {
            val state=DspState(DspConfig(false,preventClipping=false,dither=false))
            val processor=DspAudioProcessor(state)
            processor.configure(AudioProcessor.AudioFormat(48000,2,C.ENCODING_PCM_16BIT));processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
            return state to processor
        }
        val (plainState,plain)=make();val (changedState,changed)=make()
        val signal=ShortArray(400){if(it%4<2)1000 else -1000}
        assertArrayEquals(run(plain,signal),run(changed,signal))
        val boosted=plainState.config.get().copy(bands=List(10){if(it==5)6.0 else 0.0})
        plainState.config.set(boosted);changedState.config.set(boosted)
        assertArrayEquals(run(plain,signal),run(changed,signal))
        // Empty calls update the requested quantizer state without adding samples/noise.
        changedState.config.set(boosted.copy(dither=true));run(changed,shortArrayOf())
        changedState.config.set(boosted.copy(dither=false));run(changed,shortArrayOf())
        val tail=ShortArray(4000){if(it%4<2)1000 else -1000}
        assertArrayEquals(run(plain,tail),run(changed,tail))
    }
    private fun run(processor:DspAudioProcessor,input:ShortArray):ShortArray {
        val buffer=ByteBuffer.allocateDirect(input.size*2).order(ByteOrder.nativeOrder());input.forEach(buffer::putShort);buffer.flip()
        processor.queueInput(buffer);assertFalse(buffer.hasRemaining())
        val out=processor.output;return ShortArray(out.remaining()/2){out.short}
    }
}
