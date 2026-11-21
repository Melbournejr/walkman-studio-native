// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class MioDestination(val label: String, val icon: Int) {
    LIBRARY("Thư viện", R.drawable.ic_library),
    PLAYER("Đang phát", R.drawable.ic_mio_note),
    AUDIO("Audio Lab", R.drawable.ic_audio_controls),
    SETTINGS("Cài đặt", R.drawable.ic_settings)
}

@Composable
internal fun MioNavigation(active: MioDestination, select: (MioDestination) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().testTag("mio-navigation").background(colors.surface).navigationBarsPadding()) {
        HorizontalDivider(color = colors.outlineVariant)
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            MioDestination.entries.forEach { item ->
                val selected = item == active
                val ink = if (selected) colors.primary else colors.onSurfaceVariant
                Column(
                    Modifier.weight(1f).heightIn(min = 64.dp)
                        .selectable(selected, role = Role.Tab, onClick = { select(item) }).padding(vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    CompositionLocalProviderInk(ink) { StudioIcon(item.icon, modifier = Modifier.size(23.dp)) }
                    Text(item.label, color = ink, style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun CompositionLocalProviderInk(ink: Color, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides ink, content = content)
}

@Composable
internal fun MioHeader(
    title: String, centered: Boolean = false, back: (() -> Unit)? = null, editorial: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 52.dp)) {
        if (centered) {
            Text(title, Modifier.align(Alignment.Center).padding(horizontal = 96.dp).semantics { heading() },
                style = if (editorial) MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif, fontSize = 19.sp)
                else MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            back?.let { IconButton(onClick = it) { StudioIcon(if (centered) R.drawable.ic_expand else R.drawable.ic_back, "Trở lại") } }
            if (centered) Spacer(Modifier.weight(1f))
            else Text(title, Modifier.weight(1f).padding(start = if (back == null) 20.dp else 0.dp).semantics { heading() },
                style = if (editorial) MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Serif, fontSize = 28.sp)
                    else MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
        }
    }
}

@Composable
internal fun MioUnderlinedTabs(items: List<Pair<String, String>>, active: String, select: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column {
        LazyRow(Modifier.fillMaxWidth().selectableGroup(), contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            items.forEach { (id, title) ->
                item(key = id) {
                    val selected = active == id
                    Column(Modifier.width(IntrinsicSize.Max).heightIn(min = 48.dp).selectable(selected, role = Role.Tab, onClick = { select(id) }),
                        verticalArrangement = Arrangement.Bottom) {
                        Text(title, color = if (selected) colors.primary else colors.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Serif, fontSize = 15.sp),
                            modifier = Modifier.padding(bottom = 10.dp))
                        Spacer(Modifier.fillMaxWidth().height(3.dp)
                            .background(if (selected) colors.primary else Color.Transparent))
                    }
                }
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
    }
}

@Composable
internal fun MioPanelRow(icon: Int, title: String, summary: String, minHeight: Dp = 62.dp, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(scheme.surfaceContainerLow)
        .border(1.dp, scheme.outlineVariant.copy(alpha = .45f), shape)
        .clickable(role = Role.Button, onClick = onClick).heightIn(min = minHeight)
        .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProviderInk(scheme.primary) { StudioIcon(icon, modifier = Modifier.size(22.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (summary.isNotBlank()) Text(summary, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        CompositionLocalProviderInk(scheme.onSurfaceVariant) { StudioIcon(R.drawable.ic_mio_chevron, modifier = Modifier.size(16.dp)) }
    }
}

@Composable
internal fun MioAlbumTile(
    title: String,
    artist: String,
    artworkRatio: Float,
    open: () -> Unit,
    play: () -> Unit,
    art: @Composable (Modifier) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth()) {
        art(Modifier.fillMaxWidth().aspectRatio(artworkRatio).clip(shape)
            .clickable(onClickLabel = "Mở album", onClick = open).testTag("mio-album-cover"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f).heightIn(min = MioSpacing.touch)
                    .clickable(onClickLabel = "Mở album", onClick = open).padding(start = 2.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal,
                    fontSize = 15.sp, lineHeight = 18.sp,
                ), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (artist.isNotBlank()) Text(artist, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    StudioIcon(R.drawable.ic_more, "Tùy chọn album $title", Modifier.size(17.dp))
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Phát album") }, onClick = { menu = false; play() })
                    DropdownMenuItem(text = { Text("Mở album") }, onClick = { menu = false; open() })
                }
            }
        }
    }
}

internal fun mioAlbumArtworkRatio(row: Int): Float = if (row == 0) 1.03f else 1.30f
