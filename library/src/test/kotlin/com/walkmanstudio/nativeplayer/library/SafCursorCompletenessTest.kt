// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Walkman Studio Native contributors
package com.walkmanstudio.nativeplayer.library

import android.database.MatrixCursor
import android.os.Bundle
import android.provider.DocumentsContract
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SafCursorCompletenessTest {
    @Test fun emptyErrorCursorCannotBeTreatedAsSuccessfullyEmptyDirectory() {
        cursor(Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, "Provider disconnected") }).use {
            try { requireCompleteSafCursor(it); fail("Expected IOException for provider error") }
            catch (expected: IOException) { assertTrue(expected.message!!.contains("Provider disconnected")) }
        }
    }

    @Test fun loadingCursorCannotBeTreatedAsComplete() {
        cursor(Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }).use {
            try { requireCompleteSafCursor(it); fail("Expected IOException for incomplete listing") }
            catch (_: IOException) { }
        }
    }

    @Test fun successfulEmptyDirectoryIsAllowed() {
        cursor(Bundle()).use { requireCompleteSafCursor(it); assertEquals(0, it.count) }
    }

    private fun cursor(extras: Bundle) = MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
        .apply { setExtras(extras) }
}
