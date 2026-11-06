// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.media3.common.C
import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

class PlaybackPresentationTest {
    @Test fun unknownDurationUsesRoomForDisplayButKeepsPositionAndProgressAtZero() {
        val time=PlaybackTimeline.snapshot(C.TIME_UNSET,240_000,60_000,Player.STATE_READY)
        assertEquals(240_000L,time.durationMs)
        assertEquals(0L,time.positionMs)
        assertEquals(0f,time.progress,0f)
        assertFalse(time.seekEnabled)
        assertNull(PlaybackTimeline.seekTarget(1_000,C.TIME_UNSET,240_000,60_000,Player.STATE_READY))
    }

    @Test fun completelyUnknownDurationHasStableZeroPositionAndCannotSeek() {
        val time=PlaybackTimeline.snapshot(C.TIME_UNSET,null,Long.MAX_VALUE,Player.STATE_READY)
        assertEquals(0L,time.durationMs)
        assertEquals(0L,time.positionMs)
        assertEquals(0f,time.progress,0f)
        assertFalse(time.seekEnabled)
        assertNull(PlaybackTimeline.seekTarget(1_000,C.TIME_UNSET,null,Long.MAX_VALUE,Player.STATE_READY))
    }

    @Test fun knownUnseekableTimelineStillAdvancesClockAndProgress() {
        val first=PlaybackTimeline.snapshot(240_000,null,60_000,Player.STATE_READY,false)
        assertTrue(first.timelineKnown)
        assertFalse(first.seekEnabled)
        assertEquals(60_000L,first.positionMs)
        assertEquals(.25f,first.progress,0f)
        val later=PlaybackTimeline.snapshot(240_000,null,120_000,Player.STATE_READY,false)
        assertTrue(later.timelineKnown)
        assertEquals(120_000L,later.positionMs)
        assertEquals(.5f,later.progress,0f)
        assertNull(PlaybackTimeline.seekTarget(90_000,240_000,null,60_000,Player.STATE_READY,false))
    }

    @Test fun unknownOrRestoredTimelineIsIndependentOfStoredDurationAndSeekMap() {
        for (state in listOf(Player.STATE_IDLE,Player.STATE_READY)) {
            val raw=if(state==Player.STATE_IDLE)240_000L else C.TIME_UNSET
            val value=PlaybackTimeline.snapshot(raw,240_000,120_000,state,true)
            assertFalse(value.timelineKnown)
            assertFalse(value.seekEnabled)
            assertEquals(240_000L,value.durationMs)
            assertEquals(0L,value.positionMs)
            assertEquals(0f,value.progress,0f)
        }
    }

    @Test fun overshootAndNegativePositionAreClampedToThePreparedItem() {
        assertEquals(PlaybackTimeline.Timeline(1_000,1_000,true),PlaybackTimeline.snapshot(1_000,240_000,50_000,Player.STATE_READY))
        assertEquals(1f,PlaybackTimeline.snapshot(1_000,null,Long.MAX_VALUE,Player.STATE_READY).progress,0f)
        assertEquals(0L,PlaybackTimeline.snapshot(1_000,null,-100,Player.STATE_READY).positionMs)
    }

    @Test fun restoredIdleQueueIgnoresStaleControllerTimelineAndPosition() {
        val restored=PlaybackTimeline.snapshot(999_999,240_000,Long.MAX_VALUE,Player.STATE_IDLE)
        assertEquals(240_000L,restored.durationMs)
        assertEquals(0L,restored.positionMs)
        assertEquals(0f,restored.progress,0f)
        assertFalse(restored.seekEnabled)
        val unindexed=PlaybackTimeline.snapshot(999_999,null,999_998,Player.STATE_IDLE)
        assertEquals(PlaybackTimeline.Timeline(0,0,false),unindexed)
    }

    @Test fun idleSeekIsRejectedEvenWhenRoomDurationIsKnown() {
        assertNull(PlaybackTimeline.seekTarget(60_000,240_000,240_000,120_000,Player.STATE_IDLE))
        assertNull(PlaybackTimeline.seekTarget(Long.MAX_VALUE,C.TIME_UNSET,240_000,120_000,Player.STATE_IDLE))
    }

    @Test fun seekTargetsUseFreshTimelineBoundsAndRespectUnseekableStreams() {
        assertEquals(240_000L,PlaybackTimeline.seekTarget(Long.MAX_VALUE,240_000,null,120_000,Player.STATE_READY))
        assertEquals(0L,PlaybackTimeline.seekTarget(-1,240_000,null,120_000,Player.STATE_READY))
        assertNull(PlaybackTimeline.seekTarget(10,240_000,null,120_000,Player.STATE_READY,false))
        assertNull(PlaybackTimeline.seekTarget(30_000,C.TIME_UNSET,240_000,120_000,Player.STATE_BUFFERING))
        assertEquals(30_000L,PlaybackTimeline.seekTarget(30_000,240_000,null,120_000,Player.STATE_BUFFERING))
    }

    @Test fun invalidStoredDurationsNeverEnterProgressArithmetic() {
        for(stored in listOf(null,0L,-1L,C.TIME_UNSET)) {
            assertEquals(PlaybackTimeline.Timeline(0,0,false),PlaybackTimeline.snapshot(C.TIME_UNSET,stored,999_000,Player.STATE_IDLE))
        }
    }

    @Test fun actualTagsTakePriorityAndAreOnlySanitizedForDisplay() {
        val text=NowPlayingText.display("Giấc Mơ Trưa","Khánh Linh","Ban Mai","wrong_file_name_[Official Audio].aac",mediaTitle="Wrong")
        assertEquals(NowPlayingText.Display("Giấc Mơ Trưa","Khánh Linh","Ban Mai"),text)
    }

    @Test fun untaggedFileNamesLoseExtensionsUnderscoresAndOnlyKnownJunkSuffixes() {
        assertEquals("Giấc Mơ Trưa",NowPlayingText.cleanFileName("Giấc_Mơ_Trưa_[Official Audio]_[320kbps].m4a"))
        assertEquals("Đường về (Acoustic)",NowPlayingText.cleanFileName("Đường_về_(Acoustic).flac"))
        assertEquals("Tên bài",NowPlayingText.cleanFileName("Tên_bài - Lyrics.mp3"))
        assertEquals("A.mix",NowPlayingText.cleanFileName("A.mix.wav"))
    }

    @Test fun fallbackCatalogTitleReceivesTheSameCleanupAsTheOriginalFileName() {
        val file="Giấc_Mơ_Trưa_[Official Audio].aac"
        assertEquals("Giấc Mơ Trưa",NowPlayingText.display(fileName=file,mediaTitle="Giấc_Mơ_Trưa_[Official Audio]").title)
        assertEquals("Giấc Mơ Trưa",NowPlayingText.display(fileName=file).title)
    }

    @Test fun media3TagBeforeRoomUpdateStillTakesPriorityOverRawFileName() {
        assertEquals("Thật dịu dàng",NowPlayingText.display(fileName="Track_001.mp3",mediaTitle="Thật dịu dàng").title)
    }

    @Test fun urlsAndDomainsAreRemovedFromAllDisplayFieldsWithoutInventingAlbum() {
        val display=NowPlayingText.display("Giấc Mơ Trưa www.example.com","Khánh Linh https://nhac.example.net/a?b=1","nhaccuatui.com")
        assertEquals("Giấc Mơ Trưa",display.title)
        assertEquals("Khánh Linh",display.artist)
        assertEquals("",display.album)
        assertEquals("Album Việt",NowPlayingText.sanitize("Album Việt · example.net"))
    }

    @Test fun missingArtistAndAlbumStayEmptyAndUnicodeAccentsArePreserved() {
        val display=NowPlayingText.display(fileName="Những_ngày_nắng_ở_miền_ký_ức.dsf")
        assertEquals("Những ngày nắng ở miền ký ức",display.title)
        assertEquals("",display.artist)
        assertEquals("",display.album)
        assertEquals("Chưa chọn bài",NowPlayingText.display().title)
    }
}
