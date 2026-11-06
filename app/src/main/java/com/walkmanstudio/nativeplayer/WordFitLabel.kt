// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/** A label wraps at whitespace only. It never relies on paragraph emergency word breaking. */
internal object WordLabelFit {
    data class Result(val lines: List<String>, val truncated: Boolean) {
        val displayText: String get() = lines.joinToString("\n")
    }

    fun wrap(text: String, maxLines: Int = 2, fitsLine: (String) -> Boolean): Result {
        require(maxLines > 0)
        val words = text.trim().split(Regex("[\\s\\p{Z}]+")).filter(String::isNotEmpty)
        if (words.isEmpty()) return Result(emptyList(), false)
        val lines = mutableListOf<String>()
        var line = ""
        var index = 0
        while (index < words.size) {
            val candidate = if (line.isEmpty()) words[index] else "$line ${words[index]}"
            if (fitsLine(candidate)) {
                line = candidate
                index++
            } else if (line.isNotEmpty()) {
                lines += line
                line = ""
                if (lines.size == maxLines) break
            } else {
                // A single unbreakable token cannot fit. Keep the prefix; never cut the
                // token in half or jump over it to display later words from the title.
                break
            }
        }
        if (line.isNotEmpty() && lines.size < maxLines) lines += line
        val truncated = index < words.size
        if (truncated) {
            if (lines.isEmpty()) lines += ""
            var last = lines.last()
            while (last.isNotEmpty() && !fitsLine("$last…")) {
                last = last.substringBeforeLast(' ', "")
            }
            lines[lines.lastIndex] = if (fitsLine("$last…")) "$last…" else ""
        }
        return Result(lines, truncated)
    }
}

/** Font sizes stay in sp, including system fontScale. Only complete words reach each
 * line; the accessibility value always retains the entire original title.
 */
@Composable
internal fun WordFitLabel(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    testTag: String,
    minFontSp: Int = 12,
    maxFontSp: Int = 20,
) {
    require(minFontSp > 0 && maxFontSp >= minFontSp)
    val fullText = text.ifBlank { "Chưa chọn bài" }
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        val fitted = remember(fullText, widthPx, heightPx, minFontSp, maxFontSp, measurer) {
            fun style(font: Int) = TextStyle(
                color = color, fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium, fontSize = font.sp,
            )
            fun fit(font: Int): Pair<WordLabelFit.Result, Int> {
                val lineHeight = measurer.measure("Ag", style(font), softWrap = false, maxLines = 1).size.height
                val allowedLines = if (lineHeight * 2 <= heightPx) 2 else 1
                val result = WordLabelFit.wrap(fullText, allowedLines) { candidate ->
                    measurer.measure(candidate, style(font), softWrap = false, maxLines = 1).size.width <= widthPx
                }
                return result to font
            }
            (maxFontSp downTo minFontSp).asSequence().map(::fit)
                .firstOrNull { (result, font) ->
                    !result.truncated && measurer.measure(result.displayText, style(font), softWrap = false, maxLines = 2).size.height <= heightPx
                } ?: fit(minFontSp)
        }
        BasicText(
            text = fitted.first.displayText,
            modifier = Modifier.fillMaxWidth().testTag(testTag).clearAndSetSemantics {
                this.text = AnnotatedString(fullText)
            },
            style = TextStyle(color = color, fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium, fontSize = fitted.second.sp),
            maxLines = 2,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}
