// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayFormatTest {
    @Test fun missingOrInvalidSizeIsExplicit() {
        assertEquals("Chưa rõ kích thước", formatSize(null))
        assertEquals("Chưa rõ kích thước", formatSize(-1))
    }
    @Test fun smallSizesAreNotRoundedToZero() {
        assertEquals("0 B", formatSize(0))
        assertEquals("1023 B", formatSize(1023))
        assertEquals("1,0 KiB", formatSize(1024))
    }
    @Test fun dsdSizedFilesUseCorrectBinaryUnits() { assertEquals("500,0 MiB", formatSize(500L * 1024 * 1024)) }
    @Test fun veryLargeSizesDoNotOverflow() { assertTrue(formatSize(Long.MAX_VALUE).endsWith("EiB")) }
    @Test fun countAndWorkStatesUseVietnamese() {
        assertEquals("100.000", formatCount(100_000))
        assertEquals("Quét bị gián đoạn", formatScanState("INTERRUPTED"))
        assertEquals("Đã hủy quét", formatScanState("CANCELLED"))
        assertEquals("Quét chưa hoàn tất", formatScanState("FAILED"))
    }
}
