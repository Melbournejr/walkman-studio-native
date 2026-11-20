// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

class CassetteMechanicalStateTest {
    @Test fun readyAndBufferingKeepTheRequestedPlayKeyLatched() {
        assertTrue(CassetteKeyModel.latched(true,Player.STATE_READY))
        assertTrue(CassetteKeyModel.latched(true,Player.STATE_BUFFERING))
    }
    @Test fun pauseReleasesTheKeyRegardlessOfPreparedState() {
        for(state in listOf(Player.STATE_IDLE,Player.STATE_BUFFERING,Player.STATE_READY,Player.STATE_ENDED))
            assertFalse(CassetteKeyModel.latched(false,state))
    }
    @Test fun idleAndEndedReleaseEvenWhenPlayWhenReadyRemainsTrue() {
        assertFalse(CassetteKeyModel.latched(true,Player.STATE_IDLE))
        assertFalse(CassetteKeyModel.latched(true,Player.STATE_ENDED))
    }
    @Test fun externalControllerChangesRequireNoLocalClickHistory() {
        val external=listOf(true to Player.STATE_READY,false to Player.STATE_READY,
            true to Player.STATE_BUFFERING,true to Player.STATE_ENDED,true to Player.STATE_IDLE)
        assertEquals(listOf(true,false,true,false,false),external.map { CassetteKeyModel.latched(it.first,it.second) })
    }
    @Test fun momentaryKeysReleaseOnPointerUpWhilePlayRemainsLatched() {
        assertEquals(1f,CassetteKeyModel.targetDepression(false,true),0f)
        assertEquals(0f,CassetteKeyModel.targetDepression(false,false),0f)
        assertEquals(1f,CassetteKeyModel.targetDepression(true,false),0f)
        assertEquals(4f,CassetteKeyModel.DEPRESSION_DP,0f)
    }
    @Test fun reducedMotionHasNoPressAnimationDuration() {
        assertEquals(0,CassetteKeyModel.pressDurationMs(false))
        assertTrue(CassetteKeyModel.pressDurationMs(true) in 80..120)
    }
    @Test fun coastSlowsMonotonicallyAndStopsAt150Milliseconds() {
        val speeds=(0..150 step 10).map { CassetteCoast.speed(200.0,it.toDouble()) }
        assertEquals(200.0,speeds.first(),0.0)
        assertEquals(0.0,speeds.last(),0.0)
        assertTrue(speeds.zipWithNext().all { (a,b)->b<=a && b>=0 })
        assertEquals(15.0,CassetteCoast.angle(200.0,150.0),1e-9)
    }
    @Test fun stoppedCoastNeverAccumulatesBackgroundCatchUp() {
        assertEquals(0.0,CassetteCoast.angle(200.0,-10.0),0.0)
        assertEquals(CassetteCoast.angle(200.0,150.0),CassetteCoast.angle(200.0,3_600_000.0),0.0)
        assertEquals(0.0,CassetteCoast.speed(200.0,3_600_000.0),0.0)
    }
    @Test fun displayClockPreservesTheExactFirstSecondInsteadOfRoundTrippingThroughFloat() {
        val actual=CassetteDeckPosition.display(1000,180001,true)
        assertEquals(1000L,actual)
        assertEquals("0:01",clockTime(actual))
    }
    @Test fun liveClockKeepsZeroAndExactDurationEndpoint() {
        assertEquals(0L,CassetteDeckPosition.display(0,180001,true))
        assertEquals(180001L,CassetteDeckPosition.display(180001,180001,true))
    }
    @Test fun unknownAndRestoredTimelinesKeepTheClockAtZero() {
        assertEquals(0L,CassetteDeckPosition.display(1000,0,false))
        assertEquals(0L,CassetteDeckPosition.display(1000,180001,false))
        assertEquals(0L,CassetteDeckPosition.display(1000,0,true))
    }
    @Test fun liveClockClampsWithoutLosingLongPrecision() {
        assertEquals(0L,CassetteDeckPosition.display(-1,180001,true))
        assertEquals(180001L,CassetteDeckPosition.display(190000,180001,true))
        assertEquals(16_777_217L,CassetteDeckPosition.display(16_777_217,25_000_003,true))
    }
    @Test fun onlyExplicitPreviewConvertsProgressIntoClockTime() {
        assertEquals(0L,CassetteDeckPosition.display(1000,180001,true,0f))
        assertEquals(180001L,CassetteDeckPosition.display(1000,180001,true,1f))
        assertEquals(90000L,CassetteDeckPosition.display(1000,180001,true,.5f))
        assertEquals(0L,CassetteDeckPosition.display(1000,180001,false,1f))
    }

}
