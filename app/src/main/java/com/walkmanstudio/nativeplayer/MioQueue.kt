// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class MioQueueItem(val id: String, val title: String, val artist: String)

@Composable
internal fun MioQueue(
    queue: List<MioQueueItem>, activeIndex: Int, close: () -> Unit,
    select: (Int) -> Unit, remove: (Int) -> Unit,
    cover: @Composable (MioQueueItem, Modifier) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(colors.background).navigationBarsPadding()) {
        MioHeader("Hàng đợi", back = close)
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            if (queue.isEmpty()) item {
                Text("Hàng đợi đang trống.", Modifier.padding(12.dp), color = colors.onSurfaceVariant)
            }
            itemsIndexed(queue, key = { index, item -> "$index-${item.id}" }) { index, item ->
                val active = index == activeIndex
                val shape = RoundedCornerShape(10.dp)
                Row(Modifier.fillMaxWidth().clip(shape)
                    .then(if (active) Modifier.background(colors.surfaceContainerHigh) else Modifier)
                    .heightIn(min = 76.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).heightIn(min = 76.dp).clickable { select(index) }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        cover(item, Modifier.size(56.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (item.artist.isNotBlank()) Text(item.artist,
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (active) CompositionLocalProviderAccent {
                            StudioIcon(R.drawable.ic_audio_controls, "Bài hiện tại", Modifier.size(20.dp))
                        }
                    }
                    IconButton(onClick = { remove(index) }) { StudioIcon(R.drawable.ic_close, "Bỏ ${item.title} khỏi hàng đợi") }
                }
                if (!active) HorizontalDivider(color = colors.outlineVariant.copy(alpha = .45f))
            }
        }
    }
}

@Composable
internal fun CompositionLocalProviderAccent(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary, content = content)
}
