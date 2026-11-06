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
            Column(Modifier.weight(1f)) { Text("Source Direct",style=MaterialTheme.typography.titleMedium);Text("Bỏ qua EQ, Clear Bass, ReplayGain và dither. Bù mức DSD vẫn áp dụng nếu chọn.",fontSize=13.sp) }
            Switch(checked=config.sourceDirect,onCheckedChange={model.setDsp(config.copy(sourceDirect=it))},modifier=Modifier.semantics{contentDescription="Source Direct"})
        }
        Text("Đường tín hiệu",style=MaterialTheme.typography.titleMedium)
        Text("Tệp: ${track?.mimeType ?: "Chưa chọn bài"}",fontSize=14.sp)
        CassetteSignal.chips(signal,config.sourceDirect,!config.sourceDirect).forEach { chip ->
            Text(chip,fontSize=14.sp)
        }
        Text("Android có thể trộn hoặc đổi tần số sau ngõ ra ứng dụng. Chưa đo tín hiệu điện tại DAC hoặc codec Bluetooth.",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        val usb by model.usb.collectAsStateWithLifecycle()
        if(usb.connected) Text(usb.reason,fontSize=13.sp)
        if(!config.sourceDirect) Text("DSP qua PCM16 · Gain ${String.format(Locale.ROOT,"%.1f",signal.gainDb)} dB · ${signal.clipped} mẫu chạm ngưỡng",fontSize=13.sp)
        val dsdGain by model.dsdGainDb.collectAsStateWithLifecycle()
        if(dsdGain>0) Text("DSD +$dsdGain dB · ${signal.dsdOverFullScaleSamples} mẫu-kênh giải mã vượt 0 dBFS (kể cả bộ đệm/tua lại)",fontSize=13.sp)
        HorizontalDivider(Modifier.padding(vertical=8.dp))
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
        TextButton(onClick={model.setDsp(DspConfig(sourceDirect=config.sourceDirect))},modifier=Modifier.heightIn(min=48.dp)) { Text("Đặt lại âm thanh") }
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
                RadioButton(selected=gain==db,onClick={model.setDsdGain(db)},modifier=Modifier.semantics { contentDescription="Bù mức DSD ${if(db==0) "0" else "+$db"} dB" })
                Text(if(db==0) "0 dB (mặc định)" else "+$db dB", fontSize=16.sp)
            }
        }
        Text("Chỉ nhân PCM float vừa giải mã từ DSD, kể cả Source Direct. Nhạc PCM thường không bị bù mức. Áp dụng từ khối giải mã tiếp theo; âm thanh đã đệm có thể trễ.",fontSize=13.sp)
        if(gain>0) Text("${signal.dsdOverFullScaleSamples} mẫu-kênh vượt 0 dBFS từ khi đổi mức/khởi động trình phát. Bao gồm giải mã trước và tua lại; đây không phải bộ đếm clipping tại DAC.",fontSize=13.sp)
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end=12.dp)) {
                Text("Dither TPDF khi dùng EQ",fontSize=16.sp)
                Text("Lượng tử hóa cuối DSP → PCM16. Mặc định bật; Source Direct bỏ qua hoàn toàn.",fontSize=13.sp)
            }
            Switch(checked=config.dither,onCheckedChange={model.setDsp(config.copy(dither=it))},modifier=Modifier.semantics{contentDescription="Dither TPDF khi dùng EQ"})
        }
        if(config.sourceDirect) Text("Source Direct đang bật: dither được lưu nhưng chưa xử lý âm thanh.",fontSize=13.sp)
        Text("Đường EQ hiện đổi nguồn 24-bit/float về PCM16 trước EQ; dither cuối không khôi phục các bit đã mất ở bước này.",fontSize=13.sp)
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
