// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Walkman Studio Native contributors
package com.walkmanstudio.nativeplayer.core

import java.text.Normalizer
import java.util.Locale

/**
 * Precomputed natural-sort key for SQLite BINARY collation. Numeric runs are
 * compared by digit count and then digits, never parsed into an Int or Long.
 * Equal numeric values use fewer leading zeroes first. Name/documentId are
 * deterministic final SQL tie-breakers after this normalized key.
 */
fun naturalSortKey(value: String): String {
    val text = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    return buildString(text.length * 2) {
        var index = 0
        while (index < text.length) {
            if (text[index] in '0'..'9') {
                val start = index
                while (index < text.length && text[index] in '0'..'9') index++
                var significant = start
                while (significant < index - 1 && text[significant] == '0') significant++
                append('\u0001')
                append((index - significant).toString().padStart(10, '0'))
                append(text, significant, index)
                append(':')
                append((index - start).toString().padStart(10, '0'))
            } else {
                // A marker for each text code unit makes control characters
                // unambiguous with numeric token prefixes.
                append('\u0002')
                append(text[index++])
            }
        }
    }
}
