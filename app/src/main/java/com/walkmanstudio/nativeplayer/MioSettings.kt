// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.walkmanstudio.nativeplayer.update.UpdateSettings
import com.walkmanstudio.nativeplayer.update.UpdateViewModel

enum class MioSettingsSection(val label: String) {
    APPEARANCE("Giao diện"), PLAYBACK("Phát nhạc"), AUDIO("Âm thanh"),
    LIBRARY("Thư viện"), UPDATES("Cập nhật"), ABOUT("Giới thiệu");

    companion object {
        fun fromSaved(value: String) = entries.firstOrNull { it.name == value } ?: APPEARANCE
    }
}

@Composable
internal fun MioSettingsTabs(section: MioSettingsSection, select: (MioSettingsSection) -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = section.ordinal)
    LaunchedEffect(section) { state.animateScrollToItem(section.ordinal) }
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(MioSpacing.compact),
        contentPadding = PaddingValues(horizontal = MioSpacing.gutter)) {
        items(MioSettingsSection.entries, key = { it.name }) { choice ->
            FilterChip(section == choice, { select(choice) }, label = { Text(choice.label) },
                modifier = Modifier.heightIn(min = MioSpacing.touch))
        }
    }
}

@Composable
internal fun SettingsHeading(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
}

@Composable
internal fun MioSettingsContent(
    section: MioSettingsSection, model: LibraryViewModel, playback: PlaybackViewModel, updates: UpdateViewModel,
    appearance: String, displayMode: NowPlayingMode, theme: WalkmanTheme, cassetteLandscape: Boolean, total: Long,
    showDeviceHelp: () -> Unit, exportLog: () -> Unit, chooseTree: () -> Unit,
) {
    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(MioSpacing.gutter),
        verticalArrangement = Arrangement.spacedBy(MioSpacing.group)) {
        SettingsHeading(if (section == MioSettingsSection.AUDIO) "Audio Lab" else section.label)
        when (section) {
            MioSettingsSection.APPEARANCE -> {
                listOf("dark" to "Tối", "light" to "Sáng", "system" to "Theo hệ thống").forEach { (id, label) ->
                    MioRadioRow(label, appearance == id) { model.setAppearance(id) }
                }
                HorizontalDivider()
                Text("Chế độ Đang phát", style = MaterialTheme.typography.titleMedium)
                NowPlayingMode.entries.forEach { mode ->
                    MioRadioRow(mode.label, displayMode == mode) { model.setNowPlayingMode(mode) }
                }
                Text("Bảng màu retro", style = MaterialTheme.typography.titleMedium)
                WalkmanTheme.entries.forEach { color ->
                    MioRadioRow(color.model, theme == color) { model.setWalkmanTheme(color) }
                }
            }
            MioSettingsSection.PLAYBACK -> {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = MioSpacing.group)) {
                        Text("Cassette luôn ngang", style = MaterialTheme.typography.titleMedium)
                        Text("Áp dụng khi mở trình phát cassette.", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = cassetteLandscape, onCheckedChange = model::setCassetteLandscape,
                        modifier = Modifier.semantics { contentDescription = "Cassette luôn ngang" })
                }
                SystemVolumeButton()
                OutlinedButton(onClick = showDeviceHelp, modifier = Modifier.heightIn(min = MioSpacing.touch)) {
                    Text("Hướng dẫn Xiaomi / HyperOS")
                }
            }
            MioSettingsSection.AUDIO -> AudioLab(playback)
            MioSettingsSection.LIBRARY -> {
                Text("${formatCount(total)} bài", style = MaterialTheme.typography.titleMedium)
                Text("Mio đọc tệp gốc trong các thư mục bạn cấp quyền.")
                Button(onClick = chooseTree, modifier = Modifier.heightIn(min = MioSpacing.touch)) { Text("Thêm thư mục nhạc") }
                TextButton(onClick = { model.selectTab("folders") }, modifier = Modifier.heightIn(min = MioSpacing.touch)) {
                    Text("Quản lý thư mục")
                }
            }
            MioSettingsSection.UPDATES -> UpdateSettings(updates)
            MioSettingsSection.ABOUT -> {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                Text("Phiên bản ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                Text("MP3, FLAC, WAV, AAC và Ogg dùng bộ giải mã trên thiết bị. DSF và DFF không nén DSD64–256 được chuyển thành PCM 88,2 kHz.")
                Text("GPL-3.0-or-later")
                OutlinedButton(onClick = exportLog, modifier = Modifier.heightIn(min = MioSpacing.touch)) {
                    Text("Xuất log trình phát")
                }
            }
        }
    }
}

@Composable
private fun MioRadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = MioSpacing.touch)
        .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        horizontalArrangement = Arrangement.spacedBy(MioSpacing.group), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
