// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkmanstudio.nativeplayer.core.*
import java.util.Locale

@Composable
fun SignalAndDsp(model: PlaybackViewModel) {
    val config by model.dsp.collectAsStateWithLifecycle()
    val signal by model.signal.collectAsStateWithLifecycle()
    val track by model.currentTrack.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Source Direct",style=MaterialTheme.typography.titleMedium);Text("Bỏ qua EQ, Clear Bass, ReplayGain, limiter, dither và bù mức DSD.",fontSize=13.sp) }
            Switch(checked=config.sourceDirect,onCheckedChange={model.setDsp(config.copy(sourceDirect=it))},modifier=Modifier.semantics{contentDescription="Source Direct"})
        }
        MioSignalPath(signal, track?.mimeType, config.sourceDirect)
        val dsdGain by model.dsdGainDb.collectAsStateWithLifecycle()
        if (dsdGain > 0 && !config.sourceDirect) {
            Text("DSD +$dsdGain dB · ${signal.dsdOverFullScaleSamples} mẫu-kênh giải mã vượt 0 dBFS",
                style = MaterialTheme.typography.bodyMedium)
        }
        HorizontalDivider(Modifier.padding(vertical = MioSpacing.compact))
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
        Text("ReplayGain",style=MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(ReplayGainMode.OFF to "Tắt",ReplayGainMode.TRACK to "Theo bài",ReplayGainMode.ALBUM to "Theo album").forEach { (mode,label) -> item {
                FilterChip(selected=config.replayGain==mode,onClick={model.setDsp(config.copy(replayGain=mode))},enabled=!config.sourceDirect,label={Text(label)},modifier=Modifier.heightIn(min=48.dp))
            } }
        }
        Text("Tag thiếu: gain 0 dB; album thiếu thì dùng tag bài.",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
internal fun AudioLab(model: PlaybackViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(MioSpacing.group)) {
        SignalAndDsp(model)
        Stage8AudioSettings(model)
        UsbOutputSettings(model)
    }
}

@Composable
internal fun MioSignalPath(signal: SignalUi, mime: String?, sourceDirect: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = MioSpacing.compact),
        verticalArrangement = Arrangement.spacedBy(MioSpacing.group)) {
        SettingsHeading("Đường tín hiệu")
        SignalStage("Nguồn", signal.source, mime)
        SignalStage("Giải mã", signal.decoded, signal.decoder)
        val processing = when {
            signal.dspBypassActive == true -> "AudioTrack xác nhận đường bỏ qua DSP"
            sourceDirect -> "Đã chọn Source Direct"
            else -> "Gain ${String.format(Locale.ROOT, "%.1f", signal.gainDb)} dB · ${signal.clipped} mẫu vượt ngưỡng"
        }
        SignalStage("Xử lý DSP", processing)
        val match = signal.decoderMatchesAudioTrack?.let {
            if (it) "Định dạng PCM khớp bộ giải mã" else "Định dạng PCM khác bộ giải mã"
        }
        SignalStage("Ngõ ra Android", signal.output,
            listOfNotNull(signal.outputDevice, match).joinToString(" · "))
        Text("Android có thể trộn hoặc đổi tần số sau AudioTrack. Tín hiệu tại DAC hoặc Bluetooth chưa được đo.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SignalStage(title: String, value: String, detail: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value.ifBlank { "Chưa có dữ liệu" }, style = MaterialTheme.typography.bodyLarge)
        detail?.takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
