// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkmanstudio.nativeplayer.core.*
import java.util.Locale

@Composable
fun SignalAndDsp(model: PlaybackViewModel, showSourceDirect: Boolean = true) {
    val config by model.dsp.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if (showSourceDirect) Row(Modifier.fillMaxWidth().heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Source Direct",style=MaterialTheme.typography.titleMedium);Text("Bỏ qua EQ, Clear Bass, ReplayGain, limiter, dither và bù mức DSD.",fontSize=13.sp) }
            Switch(checked=config.sourceDirect,onCheckedChange={model.setDsp(config.copy(sourceDirect=it))},modifier=Modifier.semantics{contentDescription="Source Direct"})
        }
        Text("EQ 10 dải · ±12 dB",style=MaterialTheme.typography.titleMedium)
        DspConfig.FREQUENCIES.forEachIndexed { index,frequency ->
            val name=if(frequency<1000) "$frequency Hz" else "${frequency/1000} kHz"
            GainControl(name,config.bands[index].toFloat(),-12f..12f,!config.sourceDirect) { value ->
                model.setDsp(config.copy(bands=config.bands.mapIndexed { i,gain -> if(i==index)value.toDouble() else gain }))
            }
        }
        Text("Dải từ Nyquist trở lên được bỏ qua theo tần số nguồn.",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        GainControl("Clear Bass",config.clearBass.toFloat(),0f..12f,!config.sourceDirect) { model.setDsp(config.copy(clearBass=it.toDouble())) }
        GainControl("Preamp",config.preamp.toFloat(),-24f..12f,!config.sourceDirect) { model.setDsp(config.copy(preamp=it.toDouble())) }
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("Chừa headroom chống vỡ tiếng",Modifier.weight(1f),fontSize=14.sp)
            Switch(checked=config.preventClipping,onCheckedChange={model.setDsp(config.copy(preventClipping=it))},enabled=!config.sourceDirect,modifier=Modifier.semantics{contentDescription="Chống clipping"})
        }
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("Giới hạn mềm từ −0,9 dBFS",Modifier.weight(1f),fontSize=14.sp)
            Switch(checked=config.limiter,onCheckedChange={model.setDsp(config.copy(limiter=it))},
                enabled=!config.sourceDirect,modifier=Modifier.semantics{contentDescription="Giới hạn mềm"})
        }
        TextButton(onClick={model.setDsp(DspConfig(sourceDirect=config.sourceDirect))},modifier=Modifier.heightIn(min=48.dp)) { Text("Đặt lại âm thanh") }
    }
}

@Composable
private fun MioReplayGain(model: PlaybackViewModel) {
    val config by model.dsp.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ReplayGain",style=MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(ReplayGainMode.OFF to "Tắt",ReplayGainMode.TRACK to "Theo bài",ReplayGainMode.ALBUM to "Theo album").forEach { (mode,label) -> item {
                FilterChip(selected=config.replayGain==mode,onClick={model.setDsp(config.copy(replayGain=mode))},enabled=!config.sourceDirect,label={Text(label)},modifier=Modifier.heightIn(min=48.dp))
            } }
        }
        Text("Tag thiếu: gain 0 dB; album thiếu thì dùng tag bài.",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal enum class MioAudioSection(val label: String, val icon: Int) {
    EQ("Equalizer", R.drawable.ic_audio_controls),
    GAIN("ReplayGain", R.drawable.ic_repeat),
    DSD("DSD", R.drawable.ic_album),
    USB("USB DAC", R.drawable.ic_audio_controls)
}

@Composable
internal fun AudioLab(model: PlaybackViewModel) {
    val config by model.dsp.collectAsStateWithLifecycle()
    val signal by model.signal.collectAsStateWithLifecycle()
    val track by model.currentTrack.collectAsStateWithLifecycle()
    val usb by model.usb.collectAsStateWithLifecycle()
    var section by remember { mutableStateOf<MioAudioSection?>(null) }
    MioAudioOverview(signal, track?.mimeType, config, usb, select = { section = it })
    if (section == MioAudioSection.EQ) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { section = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            MioEqualizer(config, { section = null }, model::setDsp) { SignalAndDsp(model, showSourceDirect = false) }
        }
    }
    section?.takeUnless { it == MioAudioSection.EQ }?.let { selected ->
        AlertDialog(onDismissRequest = { section = null }, title = { Text(selected.label) },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    when (selected) {
                        MioAudioSection.EQ -> Unit
                        MioAudioSection.GAIN -> MioReplayGain(model)
                        MioAudioSection.DSD -> Stage8AudioSettings(model)
                        MioAudioSection.USB -> {
                            UsbOutputSettings(model)
                            Text("Android có thể trộn hoặc đổi tần số sau AudioTrack. Tín hiệu tại DAC hoặc Bluetooth chưa được đo.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { section = null }) { Text("Đóng") } })
    }
}

@Composable
internal fun MioAudioOverview(
    signal: SignalUi, mime: String?, config: DspConfig, usb: UsbSettingsUi,
    select: (MioAudioSection) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        MioSignalPath(signal, mime, config.sourceDirect, config)
        Spacer(Modifier.height(3.dp))
        MioAudioSection.entries.forEach { section ->
            val summary = when (section) {
                MioAudioSection.EQ -> if (config.sourceDirect) "Source Direct" else "EQ tham số · 10 dải"
                MioAudioSection.GAIN -> if (config.sourceDirect) "Bỏ qua bởi Source Direct" else when (config.replayGain) {
                    ReplayGainMode.OFF -> "Tắt"
                    ReplayGainMode.TRACK -> "Theo bài"
                    ReplayGainMode.ALBUM -> "Theo album"
                }
                MioAudioSection.DSD -> "DSD → PCM (FIR)"
                MioAudioSection.USB -> if (usb.connected) usb.reason else "Chưa kết nối"
            }
            MioPanelRow(section.icon, section.label, summary) { select(section) }
        }
    }
}

@Composable
internal fun MioSignalPath(signal: SignalUi, mime: String?, sourceDirect: Boolean, config: DspConfig? = null) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().background(colors.surfaceContainerLow, shape)
        .border(1.dp, colors.outlineVariant.copy(alpha = .65f), shape).padding(14.dp)
        .drawBehind {
            val axis = 19.dp.toPx()
            val start = 52.dp.toPx()
            drawLine(colors.primary.copy(alpha = .5f), Offset(axis, start), Offset(axis, (size.height - 24.dp.toPx()).coerceAtLeast(start)))
        },
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Đường tín hiệu", style = MaterialTheme.typography.titleMedium)
        SignalStage(R.drawable.ic_mio_note, "Nguồn", signal.source, mime)
        SignalStage(R.drawable.ic_audio_controls, "Giải mã", signal.decoded, signal.decoder)
        val processing = when {
            signal.dspBypassActive == true -> "AudioTrack xác nhận đường bỏ qua DSP"
            sourceDirect -> "Đã chọn Source Direct"
            else -> "Gain ${String.format(Locale.ROOT, "%.1f", signal.gainDb)} dB · ${signal.clipped} mẫu vượt ngưỡng"
        }
        val options = config?.takeUnless { sourceDirect }?.let {
            "ReplayGain ${if (it.replayGain == ReplayGainMode.OFF) "tắt" else "bật"} · " +
                "Limiter ${if (it.limiter) "bật" else "tắt"} · Dither ${if (it.dither) "bật" else "tắt"}"
        }
        SignalStage(R.drawable.ic_audio_controls, "Xử lý DSP", processing, options)
        val match = signal.decoderMatchesAudioTrack?.let {
            if (it) "PCM khớp bộ giải mã" else "PCM khác bộ giải mã"
        }
        SignalStage(R.drawable.ic_mio_output, "Ngõ ra Android", signal.output,
            listOfNotNull(signal.outputDevice, match).joinToString(" · "))
    }
}

@Composable
private fun SignalStage(icon: Int, title: String, value: String, detail: String? = null) {
    val colors = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(38.dp).background(colors.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides colors.primary) {
                StudioIcon(icon, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(value.ifBlank { "Chưa có dữ liệu" }, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            detail?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun Stage8AudioSettings(model: PlaybackViewModel) {
    val config by model.dsp.collectAsStateWithLifecycle()
    val gain by model.dsdGainDb.collectAsStateWithLifecycle()
    val signal by model.signal.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), verticalArrangement=Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("Bù mức DSD", style=MaterialTheme.typography.titleMedium)
        listOf(0,3,6).forEach { db ->
            Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically) {
                RadioButton(selected=gain==db,enabled=!config.sourceDirect,onClick={model.setDsdGain(db)},modifier=Modifier.semantics { contentDescription="Bù mức DSD ${if(db==0) "0" else "+$db"} dB" })
                Text(if(db==0) "0 dB (mặc định)" else "+$db dB", fontSize=16.sp)
            }
        }
        Text("Chỉ nhân PCM float vừa giải mã từ DSD khi tắt Source Direct. Nhạc PCM thường không bị bù mức. Áp dụng từ khối giải mã tiếp theo; âm thanh đã đệm có thể trễ.",fontSize=13.sp)
        if(gain>0 && !config.sourceDirect) Text("${signal.dsdOverFullScaleSamples} mẫu-kênh vượt 0 dBFS từ khi đổi mức/khởi động trình phát. Bao gồm giải mã trước và tua lại; đây không phải bộ đếm clipping tại DAC.",fontSize=13.sp)
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end=12.dp)) {
                Text("Dither TPDF khi dùng EQ",fontSize=16.sp)
                Text("Lượng tử hóa cuối DSP → PCM16. Mặc định bật; Source Direct bỏ qua hoàn toàn.",fontSize=13.sp)
            }
            Switch(checked=config.dither,onCheckedChange={model.setDsp(config.copy(dither=it))},modifier=Modifier.semantics{contentDescription="Dither TPDF khi dùng EQ"})
        }
        if(config.sourceDirect) Text("Source Direct đang bật: dither được lưu nhưng chưa xử lý âm thanh.",fontSize=13.sp)
        Text("DSP giữ PCM có độ phân giải cao ở ngõ ra float32 khi Media3 và Android hỗ trợ. Dither chỉ áp dụng ở ngõ ra PCM16.",fontSize=13.sp)
    }
}

@Composable
private fun GainControl(name:String,value:Float,range:ClosedFloatingPointRange<Float>,enabled:Boolean,commit:(Float)->Unit) {
    var drag by remember(name,value) { mutableStateOf<Float?>(null) }
    val displayed=drag?:value
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(name,Modifier.width(76.dp),fontSize=13.sp)
        Slider(value=displayed,valueRange=range,steps=(range.endInclusive-range.start).toInt()-1,enabled=enabled,
            onValueChange={drag=it},onValueChangeFinished={drag?.let(commit);drag=null},modifier=Modifier.weight(1f).heightIn(min=48.dp).semantics{contentDescription="$name, $displayed dB"})
        Text(String.format(Locale.ROOT,"%+.0f",displayed),Modifier.width(36.dp),fontSize=13.sp)
    }
}
