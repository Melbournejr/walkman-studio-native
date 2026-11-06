// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFileTypesTest {
    private fun candidate(name: String, mime: String = "application/octet-stream", directory: Boolean = false) =
        AudioFileTypes.isAudioCandidate(SourceDocument(name, "content://fake/$name", null, name, mime, null, null, directory))

    @Test fun supportsCommonAndDsdSuffixesCaseInsensitively() {
        for (name in listOf("A.MP3", "A.flac", "A.DSF", "A.dff", "A.m4a", "A.wv", "A.tta", "A.aiff"))
            assertTrue(name, candidate(name))
    }

    @Test fun acceptsAudioMimeWithUnknownSuffix() {
        assertTrue(candidate("track.bin", "audio/flac"))
    }

    @Test fun excludesPlaylistsEvenWhenMimeIsAudio() {
        assertFalse(candidate("queue.m3u", "audio/mpeg"))
        assertFalse(candidate("queue.bin", "audio/x-mpegurl"))
        assertFalse(candidate("queue.cue", "audio/wav"))
    }

    @Test fun excludesDirectoriesEvenWithAudioNames() {
        assertFalse(candidate("album.flac", "audio/flac", directory = true))
    }

    @Test fun excludesUnrelatedFiles() {
        assertFalse(candidate("cover.jpg", "image/jpeg"))
        assertFalse(candidate("notes.txt", "text/plain"))
    }
}
