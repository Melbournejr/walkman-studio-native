// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutorService
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@androidx.annotation.OptIn(UnstableApi::class)
class UsbAudioRouteTest {
    private lateinit var fixtureLog: LifecycleLog

    @Before fun isolateProcessSingletonFromOtherRobolectricApplications() {
        val app=RuntimeEnvironment.getApplication()
        val retained=ReflectionHelpers.getStaticField<LifecycleLog?>(LifecycleLog::class.java,"instance")
        val retainedFile=retained?.let { ReflectionHelpers.getField<File>(it,"current") }
        println("log_fixture: currentFilesDir=${app.filesDir}; retainedFile=$retainedFile; sameDirectory=${retainedFile?.parentFile==app.filesDir}")
        retained?.let(::finishWriter)
        ReflectionHelpers.setStaticField(LifecycleLog::class.java,"instance",null)
        fixtureLog=LifecycleLog.get(app)
        assertEquals("Fixture logger must belong to this test Application",app.filesDir,
            ReflectionHelpers.getField<File>(fixtureLog,"current").parentFile)
    }

    @After fun finishFixtureWriterBeforeRobolectricDeletesItsDataDirectory() {
        if(::fixtureLog.isInitialized) finishWriter(fixtureLog)
        ReflectionHelpers.setStaticField(LifecycleLog::class.java,"instance",null)
    }

    private fun finishWriter(log: LifecycleLog) {
        log.flush().get(20,TimeUnit.SECONDS)
        val writer=ReflectionHelpers.getField<ExecutorService>(log,"writer")
        writer.shutdown()
        assertTrue("Fixture writer must finish before deleting its directory",writer.awaitTermination(20,TimeUnit.SECONDS))
    }

    @Test fun decoderLogDistinguishesSource24FromDecoder16WithoutChangingFormat() {
        val input=Format.Builder().setSampleMimeType("audio/flac").setPcmEncoding(C.ENCODING_PCM_24BIT).build()
        val decoded=Format.Builder().setSampleMimeType("audio/raw").setPcmEncoding(C.ENCODING_PCM_16BIT).setSampleRate(96000).build()
        val event=OutputTelemetry.decoder("42",decoded,OutputTelemetry.sourceDepth(input))
        assertEquals(24,event.getInt("sourceBitDepth"));assertEquals(C.ENCODING_PCM_16BIT,event.getInt("pcmEncoding"))
        assertEquals("audio/raw",event.getString("sampleMimeType"));assertEquals(96000,event.getInt("sampleRate"))
        assertEquals(C.ENCODING_PCM_16BIT,decoded.pcmEncoding)
    }
    @Test fun flacStreamInfoReportsSourceBitDepthAndMalformedHeadersStayUnknown() {
        for(bits in listOf(16,24,32)) {
            val header=ByteArray(42);"fLaC".toByteArray().copyInto(header)
            header[20]=((bits-1) ushr 4).toByte();header[21]=(((bits-1) and 15) shl 4).toByte()
            val format=Format.Builder().setSampleMimeType("audio/flac").setInitializationData(listOf(header)).build()
            assertEquals(bits,OutputTelemetry.sourceDepth(format))
        }
        assertNull(OutputTelemetry.sourceDepth(Format.Builder().setSampleMimeType("audio/flac").setInitializationData(listOf(ByteArray(10))).build()))
        assertNull(OutputTelemetry.sourceDepth(Format.Builder().setSampleMimeType("audio/mp4a-latm").build()))
    }
    @Test fun structuredRouteAndDecoderEventsSurviveExportBufferWithoutTruncation() {
        val app=RuntimeEnvironment.getApplication();val log=LifecycleLog.get(app)
        val fields=JSONObject().put("apiLevel",35).put("sampleRates",org.json.JSONArray((1..400).toList()))
        log.record("output_route",fields)
        log.record("decoder_output",OutputTelemetry.decoder("43",null,null))
        log.flush().get(20,TimeUnit.SECONDS)
        val rows=File(app.filesDir,"playback-events.jsonl").readLines().map(::JSONObject)
        val route=rows.last{it.getString("event")=="output_route"}.getJSONObject("fields")
        assertEquals(400,route.getJSONArray("sampleRates").length())
        val decoder=rows.last{it.getString("event")=="decoder_output"}.getJSONObject("fields")
        assertTrue(decoder.isNull("pcmEncoding"));assertTrue(decoder.isNull("sourceBitDepth"))
        assertEquals("awaiting_decoder",decoder.getString("observation"))
    }
    @Test fun routeMeasuresActualTrackAndCleansUpUnsupportedHostWithoutEnabling() {
        val app=RuntimeEnvironment.getApplication();val log=LifecycleLog.get(app)
        val monitor=UsbAudioRoute(app,Handler(Looper.getMainLooper()),log,{true},{0},{})
        val track=AudioTrack.Builder().setAudioFormat(AudioFormat.Builder().setSampleRate(44100)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(4096).setTransferMode(AudioTrack.MODE_STREAM).build()
        try {
            assertFalse(monitor.status.requested);assertFalse(monitor.setEnabled(true,true))
            monitor.attached(track);monitor.refresh("playback_started",true)
            assertFalse(monitor.status.verified)
            log.flush().get(20,TimeUnit.SECONDS)
            val event=File(app.filesDir,"playback-events.jsonl").readLines().map(::JSONObject)
                .last{it.getString("event")=="output_route"}.getJSONObject("fields")
            assertEquals("playback_started",event.getString("reason"));assertEquals(35,event.getInt("apiLevel"))
            val audio=event.getJSONObject("audioTrack")
            assertEquals(track.sampleRate,audio.getInt("sampleRate"));assertEquals(track.audioFormat,audio.getInt("encoding"))
            assertEquals(track.audioSessionId,audio.getInt("audioSessionId"));assertEquals(track.performanceMode,audio.getInt("performanceMode"))
            assertTrue(event.has("routedDevice"));assertTrue(event.has("devices"))
        } finally {monitor.released(track);monitor.close();track.release()}
    }
}
