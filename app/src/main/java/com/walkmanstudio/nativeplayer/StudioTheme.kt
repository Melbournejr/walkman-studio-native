// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Walkman Studio Native contributors
package com.walkmanstudio.nativeplayer

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Static palettes keep semantic text contrast predictable; album themes arrive in stage7.
val StudioLight = lightColorScheme(
    primary = Color(0xFF854000), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBC4), onPrimaryContainer = Color(0xFF2D1600),
    secondary = Color(0xFF535D73), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE2F8), onSecondaryContainer = Color(0xFF151C2D),
    background = Color(0xFFFFF8F3), onBackground = Color(0xFF211B17),
    surface = Color(0xFFFFF8F3), onSurface = Color(0xFF211B17),
    surfaceVariant = Color(0xFFF1DFD2), onSurfaceVariant = Color(0xFF51443C),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF75665C),
)
val StudioDark = darkColorScheme(
    primary = Color(0xFFFFB77F), onPrimary = Color(0xFF4B2600),
    primaryContainer = Color(0xFF683500), onPrimaryContainer = Color(0xFFFFDBC4),
    secondary = Color(0xFFBCC6E2), onSecondary = Color(0xFF263147),
    secondaryContainer = Color(0xFF3C485F), onSecondaryContainer = Color(0xFFDCE2F8),
    background = Color(0xFF171310), onBackground = Color(0xFFF1E0D6),
    surface = Color(0xFF171310), onSurface = Color(0xFFF1E0D6),
    surfaceVariant = Color(0xFF51443C), onSurfaceVariant = Color(0xFFD7C2B5),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFFA99080),
)

@Composable
fun StudioTheme(appearance: String, content: @Composable () -> Unit) {
    val dark = appearance == "dark" || (appearance == "system" && isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) StudioDark else StudioLight, content = content)
}
