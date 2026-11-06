// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test

class CassetteSignalTest {
    @Test fun missingDataHidesEveryChipRatherThanInventingQuality() {
        assertTrue(CassetteSignal.chips(SignalUi()).isEmpty())
        assertTrue(CassetteSignal.chips(SignalUi(source="Hi-Res DSD128 demo.dsf",decoded="PCM ?",output="AudioTrack chưa được tạo")).isEmpty())
    }

    @Test fun dsdChipUsesConfirmedExtractorIdentityAndObservedPcmEncoding() {
        val signal=SignalUi(sourceMime="audio/raw",sourceRate=88200,sourcePcmEncoding=C.ENCODING_PCM_FLOAT,sourceDsdMultiplier=128,
            output="PCM16 · 48000 Hz · 2 kênh · offload=false")
        assertEquals(listOf("DSD128 → PCM 88,2 kHz · float","Ngõ ra · 48 kHz · 16 bit"),CassetteSignal.chips(signal))
        assertEquals("DSD128 → PCM 88,2 kHz · 16 bit",CassetteSignal.chips(signal.copy(sourcePcmEncoding=C.ENCODING_PCM_16BIT)).first())
    }

    @Test fun staleDsdSourceTextCannotMislabelTheSelectedMp3Track() {
        val signal=SignalUi(source="DSD128 · 5644800 Hz · 2 kênh · 1bit",sourceMime="audio/mpeg",sourceRate=44100,
            sourcePcmEncoding=C.ENCODING_PCM_16BIT,averageBitrate=320000)
        assertEquals(listOf("MP3","44,1 kHz","320 kb/s"),CassetteSignal.chips(signal))
    }

    @Test fun flacDisplaysOnlySelectedFormatRateDepthAndBitrate() {
        val signal=SignalUi(sourceMime="audio/flac",sourceRate=96000,sourcePcmEncoding=C.ENCODING_PCM_24BIT,averageBitrate=1411200)
        assertEquals(listOf("FLAC","96 kHz","24 bit","1411,2 kb/s"),CassetteSignal.chips(signal))
    }

    @Test fun unknownAndInvalidFieldsStayAbsent() {
        val signal=SignalUi(sourceMime="not audio",sourceRate=-1,sourcePcmEncoding=-1,averageBitrate=0,peakBitrate=-1,
            output="PCM24 · ? Hz · ? kênh · offload=false")
        assertTrue(CassetteSignal.chips(signal).isEmpty())
        assertEquals(listOf("PCM","88,2 kHz"),CassetteSignal.chips(SignalUi(sourceMime="audio/raw",sourceRate=88200)))
    }

    @Test fun peakBitrateIsMarkedAsAnUpperBoundAndAverageTakesPriority() {
        assertEquals(listOf("≤320 kb/s"),CassetteSignal.chips(SignalUi(peakBitrate=320000)))
        assertEquals(listOf("192 kb/s"),CassetteSignal.chips(SignalUi(averageBitrate=192000,peakBitrate=320000)))
    }

    @Test fun outputRequiresCanonicalObservedFieldsAndMakesNoDacClaim() {
        assertEquals(listOf("Ngõ ra · 88,2 kHz · float"),CassetteSignal.chips(SignalUi(output="PCM float32 · 88200 Hz · 2 kênh · offload=false")))
        for(output in listOf("PCM16 · 0 Hz · 2 kênh · offload=false","PCM16 · 48000 Hz · 0 kênh · offload=false","Hi-Res USB 192 kHz","PCM16 · 48000 Hz · 2 kênh")) {
            assertTrue(output,CassetteSignal.chips(SignalUi(output=output)).isEmpty())
        }
    }

    @Test fun dsdLabelsFromOtherExtractorsOrUnsupportedRatesCannotCreateDsdIdentity() {
        assertEquals(128,CassetteSignal.dsdMultiplier("dsd-pcm","audio/raw",listOf("DSD128 → PCM")))
        assertNull(CassetteSignal.dsdMultiplier("0","audio/raw",listOf("DSD128 → PCM")))
        assertNull(CassetteSignal.dsdMultiplier("dsd-pcm","audio/flac",listOf("DSD128 → PCM")))
        assertNull(CassetteSignal.dsdMultiplier("dsd-pcm","audio/raw",listOf("DSD512 → PCM")))
        assertFalse(CassetteSignal.chips(SignalUi(sourceMime="audio/raw",sourceRate=48000,sourceDsdMultiplier=128)).any { it.startsWith("DSD") })
    }

    @Test fun aacContainerBitrateIsHiddenWithoutFileLevelValidation() {
        val aac=SignalUi(sourceMime="audio/mp4a-latm",sourceRate=44100,averageBitrate=400118,peakBitrate=512000)
        assertEquals(listOf("AAC","44,1 kHz"),CassetteSignal.chips(aac))
        assertEquals(listOf("AAC","44,1 kHz"),CassetteSignal.chips(aac.copy(averageBitrate=null)))
        // The same raw metadata outside AAC retains the old bits/s-to-kb/s conversion.
        assertEquals(listOf("MP3","44,1 kHz","400,118 kb/s"),CassetteSignal.chips(aac.copy(sourceMime="audio/mpeg")))
    }

    @Test fun directAndEqChipsReflectTheActualConfigurationWithoutApiClassNames() {
        val observed=SignalUi(output="PCM16 · 44100 Hz · 2 kênh · offload=false")
        assertEquals(listOf("Ngõ ra · 44,1 kHz · 16 bit","EQ"),CassetteSignal.chips(observed,eqEnabled=true))
        assertEquals(listOf("Source Direct"),CassetteSignal.chips(observed,sourceDirect=true,eqEnabled=true))
        assertEquals(listOf("EQ"),CassetteSignal.chips(SignalUi(),eqEnabled=true))
        assertTrue(CassetteSignal.chips(observed).none { "AudioTrack" in it || "offload" in it })
    }
}
