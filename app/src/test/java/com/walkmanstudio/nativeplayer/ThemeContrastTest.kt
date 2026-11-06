// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test fun semanticTextPairsMeetWcagAA() {
        for (scheme in listOf(StudioLight, StudioDark)+WalkmanTheme.entries.map {it.colors}) {
            for ((foreground, background) in textPairs(scheme)) {
                val ratio = (max(luminance(foreground), luminance(background)) + 0.05) /
                    (min(luminance(foreground), luminance(background)) + 0.05)
                assertTrue("Text contrast $ratio is below 4.5", ratio >= 4.5)
            }
        }
    }
    @Test fun legendDisplayAndChromeTextKeepContrastAndPersistableIdsAreUnique() {
        assertTrue(WalkmanTheme.entries.map{it.id}.toSet().size==4)
        for(theme in WalkmanTheme.entries) {
            for((fg,bg) in listOf(theme.displayInk to theme.display,theme.ink to theme.body,theme.ink to theme.face,theme.accent to theme.body)) {
                val ratio=(max(luminance(fg),luminance(bg))+0.05)/(min(luminance(fg),luminance(bg))+0.05)
                assertTrue("${theme.id}: $ratio",ratio>=4.5)
            }
            assertTrue(WalkmanTheme.find(theme.id)==theme)
        }
        assertTrue(WalkmanTheme.find("future-theme")==WalkmanTheme.TPS_L2)
    }
    private fun textPairs(s: ColorScheme) = listOf(
        s.onPrimary to s.primary, s.onPrimaryContainer to s.primaryContainer,
        s.onSecondary to s.secondary, s.onSecondaryContainer to s.secondaryContainer,
        s.onBackground to s.background, s.onSurface to s.surface,
        s.onSurfaceVariant to s.surfaceVariant, s.onError to s.error,
        s.onErrorContainer to s.errorContainer,
    )
    private fun luminance(color: Color): Double {
        fun linear(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }
}
