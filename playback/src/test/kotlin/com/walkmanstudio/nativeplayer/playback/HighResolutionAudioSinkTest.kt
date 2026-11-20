// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.walkmanstudio.nativeplayer.core.DspConfig
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(UnstableApi::class)
class HighResolutionAudioSinkTest {
    private class Downstream(private val floatSupported: Boolean) {
        lateinit var config: AudioSink.AudioSinkConfig
        var accepts = false
        val input = mutableListOf<ByteBuffer>()
        val floatSamples = mutableListOf<Float>()
        val sink: AudioSink = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) {
                _, method, args ->
            when (method.name) {
                "getFormatSupport" -> if ((args[0] as Format).pcmEncoding != C.ENCODING_PCM_FLOAT || floatSupported)
                    AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else AudioSink.SINK_FORMAT_UNSUPPORTED
                "configure" -> { config = args[0] as AudioSink.AudioSinkConfig; null }
                "handleBuffer" -> {
                    val buffer = args[0] as ByteBuffer
                    input += buffer
                    if (config.format.pcmEncoding == C.ENCODING_PCM_FLOAT) {
                        floatSamples += buffer.float
                    } else {
                        buffer.position(buffer.limit())
                    }
                    accepts
                }
                "flush" -> null
                else -> error("Unexpected delegate call ${method.name}")
            }
        } as AudioSink
    }

    @Test fun floatConversionRetainsRateGaplessMetadataAndBufferIdentityAcrossRetries() {
        val downstream = Downstream(true)
        val wrapper = HighResolutionAudioSink(downstream.sink, DspState(DspConfig(false))) {}
        val config = AudioSink.AudioSinkConfig.Builder(format()).build()
        wrapper.configure(config)
        assertEquals(96000, downstream.config.format.sampleRate)
        assertEquals(1, downstream.config.format.channelCount)
        assertEquals(32, downstream.config.format.encoderDelay)
        assertEquals(64, downstream.config.format.encoderPadding)
        assertEquals(C.ENCODING_PCM_FLOAT, downstream.config.format.pcmEncoding)
        val pcm = ByteBuffer.wrap(byteArrayOf(1, 0, 0, 0, 0, -128)).order(ByteOrder.LITTLE_ENDIAN)
        assertFalse(wrapper.handleBuffer(pcm, 12345L, 1))
        assertEquals(0, pcm.position())
        downstream.accepts = true
        assertTrue(wrapper.handleBuffer(pcm, 12345L, 1))
        assertSame(downstream.input[0], downstream.input[1])
        assertEquals(listOf(1f / 8388608, -1f), downstream.floatSamples)
        assertFalse(pcm.hasRemaining())
    }

    @Test fun rejectsFloatWhenUnavailableAndNegotiatesPcm16AtTheSameRate() {
        val downstream = Downstream(false)
        val wrapper = HighResolutionAudioSink(downstream.sink, DspState(DspConfig(false))) {}
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING, wrapper.getFormatSupport(format()))
        wrapper.configure(AudioSink.AudioSinkConfig.Builder(format()).build())
        assertEquals(C.ENCODING_PCM_16BIT, downstream.config.format.pcmEncoding)
        assertEquals(96000, downstream.config.format.sampleRate)
    }

    private fun format() = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setSampleRate(96000)
        .setChannelCount(1).setPcmEncoding(C.ENCODING_PCM_24BIT).setEncoderDelay(32).setEncoderPadding(64).build()
}
