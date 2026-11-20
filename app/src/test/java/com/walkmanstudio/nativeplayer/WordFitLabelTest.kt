// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import org.junit.Assert.*
import org.junit.Test

class WordFitLabelTest {
    @Test fun vietnameseDiacriticsWrapOnlyBetweenCompleteWords() {
        val result = WordLabelFit.wrap("Giấc Mơ Trưa dịu dàng") { it.length <= 12 }
        assertEquals(listOf("Giấc Mơ Trưa", "dịu dàng"), result.lines)
        assertFalse(result.truncated)
    }

    @Test fun overlongTitlesEndWithEllipsisAfterAWholeWord() {
        val result = WordLabelFit.wrap("Những ngày nắng rất khẽ trên miền ký ức") { it.length <= 13 }
        assertEquals(listOf("Những ngày", "nắng rất khẽ…"), result.lines)
        assertTrue(result.truncated)
        assertTrue(result.lines.all { it.length <= 13 })
    }

    @Test fun aSingleOverlongTokenIsNotSplitOrReplacedWithLaterWords() {
        val result = WordLabelFit.wrap("TênBàiHátRấtDàiKhôngCóKhoảngTrắng phần sau") { it.length <= 8 }
        assertEquals(listOf("…"), result.lines)
        assertTrue(result.truncated)
        assertFalse(result.displayText.contains("phần"))
    }

    @Test fun anOverlongTokenAfterAPrefixPreservesOnlyTheReadablePrefix() {
        val result = WordLabelFit.wrap("Mưa TênBàiHátRấtDàiKhôngCóKhoảngTrắng phần sau") { it.length <= 8 }
        assertEquals(listOf("Mưa…"), result.lines)
        assertTrue(result.truncated)
    }

    @Test fun narrowOneLineAndUnicodeSpacesRemainWordSafe() {
        val result = WordLabelFit.wrap("Mưa\u00a0chiều\ttrên phố", maxLines = 1) { it.length <= 11 }
        assertEquals(listOf("Mưa chiều…"), result.lines)
        assertTrue(result.truncated)
        assertEquals(emptyList<String>(), WordLabelFit.wrap("  \t") { true }.lines)
    }
}
