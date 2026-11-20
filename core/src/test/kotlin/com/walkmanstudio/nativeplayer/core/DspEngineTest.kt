// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.core

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class DspEngineTest {
    private fun response(config: DspConfig, frequency: Double, tags: ReplayGainInfo = ReplayGainInfo(), rate: Int = 48000): Double {
        val engine = DspEngine(rate,2,config,tags); var inputPower=0.0;var outputPower=0.0
        for (i in 0 until rate/2) {
            val x=0.02*sin(2*PI*frequency*i/rate); val y=engine.process(x,0); assertEquals(y,engine.process(x,1),1e-12)
            if (i>rate/4) { inputPower+=x*x;outputPower+=y*y }
        }
        return 10*log10(outputPower/inputPower)
    }
    @Test fun eachGraphicBandHasSpecifiedGainAtCenter() {
        for (band in 0..9) for (gain in listOf(-12.0,6.0,12.0)) {
            val settings=DspConfig(false,List(10) { if (it==band) gain else 0.0 },preventClipping=false)
            assertEquals("band=$band gain=$gain",gain,response(settings,DspConfig.FREQUENCIES[band].toDouble()),0.06)
        }
    }
    @Test fun sourceDirectPreservesEveryInputDespiteAllDspSettings() {
        val engine=DspEngine(88200,2,DspConfig(true,List(10){12.0},12.0,12.0,ReplayGainMode.ALBUM),ReplayGainInfo(-9.0,-8.0,0.9,0.8))
        for (x in listOf(-1.0,-0.5,-0.0,0.0,0.123456789,0.99,1.0)) assertEquals(java.lang.Double.doubleToRawLongBits(x),java.lang.Double.doubleToRawLongBits(engine.process(x,0)))
    }
    @Test fun replayGainTrackAlbumAndFallbackApplyTheirRealTags() {
        val tags=ReplayGainInfo(-4.0,-8.0,0.5,0.6)
        assertEquals(-4.0,response(DspConfig(false,replayGain=ReplayGainMode.TRACK,preventClipping=false),1000.0,tags),0.005)
        assertEquals(-8.0,response(DspConfig(false,replayGain=ReplayGainMode.ALBUM,preventClipping=false),1000.0,tags),0.005)
        assertEquals(-4.0,response(DspConfig(false,replayGain=ReplayGainMode.ALBUM,preventClipping=false),1000.0,tags.copy(albumGain=null)),0.005)
    }
    @Test fun clearBassBoostsBassAndLeavesHighFrequencyNearFlat() {
        val config=DspConfig(false,clearBass=9.0,preventClipping=false)
        assertEquals(9.0,response(config,10.0),0.1);assertEquals(0.0,response(config,4000.0),0.05)
    }
    @Test fun headroomAccountsForReplayGainPeakAndEq() {
        val engine=DspEngine(48000,2,DspConfig(false,List(10){if(it==5)12.0 else 0.0},replayGain=ReplayGainMode.TRACK),ReplayGainInfo(6.0,trackPeak=1.0))
        assertTrue(engine.appliedGainDb < -11.8)
        repeat(48000) { i -> val x=0.8*sin(2*PI*1000*i/48000);assertTrue(abs(engine.process(x,0))<=1);engine.process(x,1) }
        assertEquals(0L,engine.clippedSamples)
    }
    @Test fun channelStateIsIndependentAndNyquistBandsRemainStable() {
        val engine=DspEngine(22050,2,DspConfig(false,List(10){12.0},12.0,preventClipping=false))
        repeat(44100) { i -> assertTrue(engine.process(if(i==0)0.1 else 0.0,0).isFinite());assertEquals(0.0,engine.process(0.0,1),0.0) }
        engine.update(DspConfig(false,List(10){-12.0}),ReplayGainInfo());repeat(1000) { assertTrue(engine.process(0.0,0).isFinite());engine.process(0.0,1) }
    }
    @Test fun invalidConfigurationCannotPoisonAudioThread() {
        for (value in listOf(Double.NaN,Double.POSITIVE_INFINITY,13.0,-13.0)) {
            try { DspConfig(bands=List(10){value});fail("Invalid EQ accepted") }catch(_:IllegalArgumentException){}
        }
        try{DspConfig(bands=emptyList());fail("Invalid band count")}catch(_:IllegalArgumentException){}
    }
}
