// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Walkman Studio Native contributors
package com.walkmanstudio.nativeplayer

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object MioSpacing {
    val compact = 8.dp
    val group = 12.dp
    val gutter = 20.dp
    val touch = 48.dp
}

val StudioLight = lightColorScheme(
    primary = Color(0xFF705226), onPrimary = Color.White,
    primaryContainer = Color(0xFFF1DEBD), onPrimaryContainer = Color(0xFF32230E),
    secondary = Color(0xFF4B5D70), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E7EE), onSecondaryContainer = Color(0xFF202D39),
    background = Color(0xFFF4F0E9), onBackground = Color(0xFF1D242D),
    surface = Color(0xFFF4F0E9), onSurface = Color(0xFF1D242D),
    surfaceVariant = Color(0xFFE9E6E0), onSurfaceVariant = Color(0xFF4C5967),
    surfaceContainerLow = Color(0xFFF4F1EA), surfaceContainer = Color(0xFFEDEAE4),
    surfaceContainerHigh = Color(0xFFE8E5DF), surfaceContainerHighest = Color(0xFFE2DED6),
    outline = Color(0xFF747C85), outlineVariant = Color(0xFFCFD3D8),
)

val StudioDark = darkColorScheme(
    primary = Color(0xFFD9B77A), onPrimary = Color(0xFF261B0D),
    primaryContainer = Color(0xFF423321), onPrimaryContainer = Color(0xFFF0D8B2),
    secondary = Color(0xFFB8C5D3), onSecondary = Color(0xFF232E3A),
    secondaryContainer = Color(0xFF2B3642), onSecondaryContainer = Color(0xFFD9E4EF),
    background = Color(0xFF0B0F14), onBackground = Color(0xFFF5F6F7),
    surface = Color(0xFF0B0F14), onSurface = Color(0xFFF5F6F7),
    surfaceVariant = Color(0xFF29333E), onSurfaceVariant = Color(0xFFA9B4C2),
    surfaceContainerLowest = Color(0xFF0D1117), surfaceContainerLow = Color(0xFF151B24),
    surfaceContainer = Color(0xFF1A222C), surfaceContainerHigh = Color(0xFF232B35),
    surfaceContainerHighest = Color(0xFF303A45),
    outline = Color(0xFF8B98A8), outlineVariant = Color(0xFF39434F),
)

private val baseTypography = Typography()
private val MioTypography = baseTypography.copy(
    headlineMedium = baseTypography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineSmall = baseTypography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = baseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = baseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
internal fun mioDarkTheme(appearance: String) =
    appearance == "dark" || (appearance == "system" && isSystemInDarkTheme())

@Composable
fun StudioTheme(appearance: String, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (mioDarkTheme(appearance)) StudioDark else StudioLight,
        typography = MioTypography,
        content = content,
    )
}
