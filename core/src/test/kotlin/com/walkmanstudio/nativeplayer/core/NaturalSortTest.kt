// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.core

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalSortTest {
    @Test fun sortsNumericRuns() {
        val input = listOf("Track 10.flac", "Track 2.flac", "Track 1.flac")
        assertEquals(listOf("Track 1.flac", "Track 2.flac", "Track 10.flac"), input.sortedBy(::naturalSortKey))
    }

    @Test fun handlesNumbersBeyondLong() {
        val smaller = "1".repeat(100) + ".dsf"
        val larger = "9".repeat(101) + ".dsf"
        assertTrue(naturalSortKey(smaller) < naturalSortKey(larger))
    }

    @Test fun leadingZeroTiesUseShorterRunFirst() {
        assertEquals(listOf("2", "02", "002"), listOf("002", "02", "2").sortedBy(::naturalSortKey))
    }

    @Test fun normalizesUnicodeAndCase() {
        assertEquals(naturalSortKey("ＡＢＣ１２.flac"), naturalSortKey("abc12.FLAC"))
    }

    @Test fun ignoresDeviceTurkishLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(naturalSortKey("INTRO1"), naturalSortKey("intro1"))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun handlesEmptyAndMultipleRuns() {
        assertEquals("", naturalSortKey(""))
        assertEquals(listOf("D1T2", "D1T10", "D2T1"), listOf("D2T1", "D1T10", "D1T2").sortedBy(::naturalSortKey))
    }
}
