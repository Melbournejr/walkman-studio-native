// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.core

import org.junit.Assert.*
import org.junit.Test

class UsbMixerTest {
    private fun fmt(rate:Int=44100,kind:PcmKind=PcmKind.PCM16,channels:Int=2)=PcmFormat(rate,kind,channels)
    private fun option(rate:Int=44100,kind:PcmKind=PcmKind.PCM16,direct:Boolean=true)=MixerOption(fmt(rate,kind),direct)
    private val usb=listOf(UsbEndpoint(7,true))
    @Test fun matches16Bit44100Exactly() {
        val source=DecodedPcm(fmt(),16)
        val choice=UsbMixerSelector.select(listOf(option(48000),option()),source)
        assertEquals(option(),choice.option);assertTrue(choice.exact);assertFalse(choice.conversion)
    }
    @Test fun matches24Bit96And192WithoutDependingOnListOrder() {
        for(rate in listOf(96000,192000)) {
            val target=option(rate,PcmKind.PCM24)
            val selected=UsbMixerSelector.select(listOf(option(rate,PcmKind.PCM32),target,option()),DecodedPcm(target.format,24))
            assertEquals(target,selected.option);assertTrue(selected.exact)
        }
    }
    @Test fun source24MaySelect32ContainerButDeclaresConversion() {
        val choice=UsbMixerSelector.select(listOf(option(96000,PcmKind.PCM32)),DecodedPcm(fmt(96000,PcmKind.PCM24),24))
        assertEquals(PcmKind.PCM32,choice.option!!.format.encoding);assertFalse(choice.exact);assertTrue(choice.conversion)
    }
    @Test fun dsdFloat88200MatchesAdvertisedFloat() {
        val expected=option(88200,PcmKind.FLOAT)
        val choice=UsbMixerSelector.select(listOf(option(88200),expected),DecodedPcm(expected.format,1,true))
        assertEquals(expected,choice.option);assertTrue(choice.exact)
    }
    @Test fun dsdIntegerFallbackUsesHighestDeclaredDepthAtSameRate() {
        val choice=UsbMixerSelector.select(listOf(option(88200),option(88200,PcmKind.PCM24),option(88200,PcmKind.PCM32)),DecodedPcm(fmt(88200,PcmKind.FLOAT),1,true))
        assertEquals(PcmKind.PCM32,choice.option!!.format.encoding);assertTrue(choice.conversion);assertFalse(choice.exact)
        assertTrue(choice.reason.contains("không đổi tần số"))
    }
    @Test fun neverInventsFloatOrResamplesTo48k() {
        for(source in listOf(DecodedPcm(fmt(),16),DecodedPcm(fmt(96000,PcmKind.PCM24),24),
            DecodedPcm(fmt(192000,PcmKind.PCM24),24),DecodedPcm(fmt(88200,PcmKind.FLOAT),1,true))) {
            val choice=UsbMixerSelector.select(listOf(option(48000)),source)
            assertNull(choice.option);assertFalse(choice.exact);assertFalse(choice.conversion)
        }
    }
    @Test fun emptyCapabilitiesAndDefaultMixerCannotConfirm() {
        for(options in listOf(emptyList(),listOf(option(direct=false)))) {
            val choice=UsbMixerSelector.select(options,DecodedPcm(fmt()))
            assertNull(choice.option);assertFalse(choice.exact);assertTrue(choice.reason.isNotBlank())
        }
    }
    @Test fun wrongChannelCountOrUnknownRateDoesNotMatch() {
        for(source in listOf(DecodedPcm(fmt(channels=1)),DecodedPcm(fmt(rate=0))))
            assertNull(UsbMixerSelector.select(listOf(option()),source).option)
    }
    @Test fun pcmFloatOnlyMatchesAnExplicitFloatProfile() {
        val choice=UsbMixerSelector.select(listOf(option(96000,PcmKind.PCM32)),DecodedPcm(fmt(96000,PcmKind.FLOAT)))
        assertNull(choice.option);assertFalse(choice.exact)
    }
    @Test fun reports24BitSourceDecodedTo16RatherThanConfirming() {
        val choice=UsbMixerSelector.select(listOf(option()),DecodedPcm(fmt(),24))
        assertFalse(choice.exact);assertTrue(choice.conversion);assertTrue(choice.reason.contains("Decoder"))
    }
    @Test fun onlyConfirmsAllConditionsAndUnityVolume() {
        for(preferred in listOf(false,true)) for(created in listOf(false,true)) for(routed in listOf(false,true)) for(direct in listOf(false,true)) {
            val access=FakeAudioManager();access.confirm=preferred
            val actual=if(created)ActualPcm(fmt(),7,routed,1f) else null
            val status=UsbMixerSession(access).evaluate(34,true,usb,DecodedPcm(fmt()),actual,direct,0)
            val expected=preferred && created && routed && direct
            assertEquals("preferred=$preferred created=$created routed=$routed direct=$direct",expected,status.verified)
            assertEquals(if(expected)"Bit-perfect USB" else "USB · Mixer hệ thống",status.label)
        }
    }
    @Test fun mismatchedActualRateEncodingChannelsAndDuckNeverConfirm() {
        for(actual in listOf(ActualPcm(fmt(48000),7,true,1f),ActualPcm(fmt(kind=PcmKind.FLOAT),7,true,1f),
            ActualPcm(fmt(channels=1),7,true,1f),ActualPcm(fmt(),7,true,.2f))) {
            assertFalse(UsbMixerSession(FakeAudioManager()).evaluate(35,true,usb,DecodedPcm(fmt()),actual,true,0).verified)
        }
    }
    @Test fun dsdGainAndDspClearOwnedPreference() {
        for(disableDspBypass in listOf(false,true)) {
            val access=FakeAudioManager();val session=UsbMixerSession(access)
            assertTrue(session.evaluate(34,true,usb,DecodedPcm(fmt()),ActualPcm(fmt(),7,true,1f),true,0).verified)
            val after=session.evaluate(34,true,usb,DecodedPcm(fmt()),ActualPcm(fmt(),7,true,1f),!disableDspBypass,if(disableDspBypass)0 else 3)
            assertFalse(after.verified);assertEquals(listOf(7),access.cleared)
        }
    }
    @Test fun unplugClearsEvenAfterDeviceDisappears() {
        val access=FakeAudioManager();val session=UsbMixerSession(access)
        session.evaluate(34,true,usb,DecodedPcm(fmt()),ActualPcm(fmt(),7,true,1f),true,0)
        val state=session.evaluate(34,true,emptyList(),DecodedPcm(fmt()),null,true,0)
        assertFalse(state.available);assertFalse(state.verified);assertEquals(listOf(7),access.cleared)
    }
    @Test fun routeSwitchAndUserSwitchOffCleanUp() {
        for(routeChange in listOf(false,true)) {
            val access=FakeAudioManager();val session=UsbMixerSession(access)
            session.evaluate(34,true,usb,DecodedPcm(fmt()),ActualPcm(fmt(),7,true,1f),true,0)
            val devices=usb+UsbEndpoint(8,false)
            val actual=ActualPcm(fmt(),if(routeChange)8 else 7,!routeChange,1f)
            assertFalse(session.evaluate(34,routeChange,devices,DecodedPcm(fmt()),actual,true,0).verified)
            assertEquals(listOf(7),access.cleared)
        }
    }
    @Test fun serviceStopRestoresPreviousPreferenceAndIsIdempotent() {
        val previous=option(48000,direct=false)
        val access=FakeAudioManager().apply{current=previous};val session=UsbMixerSession(access)
        session.evaluate(34,true,usb,DecodedPcm(fmt()),null,true,0)
        session.release();session.release()
        assertEquals(previous,access.current);assertEquals(listOf(7),access.cleared)
    }
    @Test fun setterRejectionAndExternalPreferenceChangeNeverConfirm() {
        val access=FakeAudioManager().apply{accept=false};val session=UsbMixerSession(access)
        assertFalse(session.evaluate(34,true,usb,DecodedPcm(fmt()),ActualPcm(fmt(),7,true,1f),true,0).verified)
        access.accept=true
        assertTrue(session.evaluate(34,true,usb,DecodedPcm(fmt()),ActualPcm(fmt(),7,true,1f),true,0).verified)
        access.current=null
        assertFalse(session.evaluate(34,true,usb,DecodedPcm(fmt()),ActualPcm(fmt(),7,true,1f),true,0).verified)
        assertEquals(2,access.setCalls)
    }
    @Test fun pre34NeverCallsMixerApis() {
        val access=object:MixerAccess {
            override fun supported(deviceId:Int):List<MixerOption> = error("API34 called")
            override fun preferred(deviceId:Int):MixerOption? = error("API34 called")
            override fun set(deviceId:Int,option:MixerOption):Boolean = error("API34 called")
            override fun clear(deviceId:Int):Boolean = error("API34 called")
        }
        val state=UsbMixerSession(access).evaluate(33,true,usb,DecodedPcm(fmt()),null,true,0)
        assertFalse(state.available);assertFalse(state.verified)
    }
    @Test fun directSwitchAloneCannotConfirmAnOldDspAudioTrack() {
        val access=FakeAudioManager();val session=UsbMixerSession(access)
        val actual=ActualPcm(fmt(),7,true,1f,bypassesDsp=true)
        assertTrue(session.evaluate(34,true,usb,DecodedPcm(fmt()),actual,true,0).verified)
        val oldDspTrack=actual.copy(bypassesDsp=false)
        val state=session.evaluate(34,true,usb,DecodedPcm(fmt()),oldDspTrack,true,0)
        assertFalse(state.verified);assertEquals("USB · Mixer hệ thống",state.label)
        assertEquals(listOf(7),access.cleared)
    }
    private class FakeAudioManager:MixerAccess {
        var current:MixerOption?=null;var accept=true;var confirm=true;var setCalls=0
        val cleared=mutableListOf<Int>()
        override fun supported(deviceId:Int)=listOf(MixerOption(PcmFormat(44100,PcmKind.PCM16,2),true))
        override fun preferred(deviceId:Int)=if(confirm)current else null
        override fun set(deviceId:Int,option:MixerOption):Boolean {setCalls++;if(accept)current=option;return accept}
        override fun clear(deviceId:Int):Boolean {cleared+=deviceId;current=null;return true}
    }
}
