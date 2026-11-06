// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import org.junit.Assert.assertEquals
import org.junit.Test

/** Independent expectations for filenames; existing presentation tests remain unchanged. */
class FilenameCleanupRegressionTest {
    @Test fun completeKnownSuffixesAreRemovedRepeatedly() {
        assertEquals("Giấc Mơ Trưa",NowPlayingText.cleanFileName("Giấc_Mơ_Trưa_[Official Audio]_[320kbps].m4a"))
    }

    @Test fun acousticAndLiveVersionsAreMeaningfulTitleContent() {
        assertEquals("Giấc Mơ Trưa (Acoustic)",NowPlayingText.cleanFileName("Giấc Mơ Trưa (Acoustic).m4a"))
        assertEquals("Giấc Mơ Trưa (Live)",NowPlayingText.cleanFileName("Giấc Mơ Trưa (Live).wav"))
    }

    @Test fun remixSurvivesRemovalOfTheFollowingKnownSuffix() {
        assertEquals("Tên bài [Remix]",NowPlayingText.cleanFileName("Tên bài [Remix] [Official Audio].flac"))
        assertEquals("Tên bài [Remix] (Live)",NowPlayingText.cleanFileName("Tên bài [Remix] (Live) [HQ].flac"))
    }

    @Test fun missingClosingBracketNeverDeletesPartOfTheTitle() {
        assertEquals("Tên bài [Official Audio",NowPlayingText.cleanFileName("Tên bài [Official Audio.mp3"))
        assertEquals("Tên bài (Live",NowPlayingText.cleanFileName("Tên bài (Live.mp3"))
    }

    @Test fun missingOpeningOrMismatchedBracketsArePreservedWithoutGuessing() {
        assertEquals("Tên bài Official Audio]",NowPlayingText.cleanFileName("Tên bài Official Audio].mp3"))
        assertEquals("Tên bài [Official Audio)",NowPlayingText.cleanFileName("Tên bài [Official Audio).mp3"))
        assertEquals("Tên bài (Official Video]",NowPlayingText.cleanFileName("Tên bài (Official Video].mp3"))
    }

    @Test fun urlsAndDomainsAreFilteredAfterRemovingTheFileExtension() {
        assertEquals("Tên bài",NowPlayingText.cleanFileName("Tên bài - www.ChiaSeNhac.com.mp3"))
        assertEquals("Tên bài (Live)",NowPlayingText.cleanFileName("Tên bài (Live) - example.net.flac"))
    }

    @Test fun onlyWholeWhitelistedGroupsAreRemoved() {
        for (suffix in listOf("[Official Audio]","(Official Video)","[320kbps]","[Lyrics]","(HQ)")) {
            assertEquals("Tên bài",NowPlayingText.cleanFileName("Tên_bài $suffix.mp3"))
        }
        for (suffix in listOf("[Official Audio Remix]","(Live)","[Remix]","(Acoustic)","[My HQ Mix]")) {
            assertEquals("Tên bài $suffix",NowPlayingText.cleanFileName("Tên_bài $suffix.mp3"))
        }
    }

    @Test fun tagSanitizationPreservesValidAndUnmatchedBracketContent() {
        assertEquals("Tên bài (Live)",NowPlayingText.sanitize("Tên bài (Live)"))
        assertEquals("[Remix]",NowPlayingText.sanitize("[Remix]"))
        assertEquals("Tên bài [Official Audio",NowPlayingText.sanitize("Tên bài [Official Audio"))
        assertEquals("",NowPlayingText.sanitize("[]"))
    }
}
