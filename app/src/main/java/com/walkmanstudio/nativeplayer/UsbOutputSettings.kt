// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun UsbOutputSettings(model: PlaybackViewModel) {
    val state by model.usb.collectAsStateWithLifecycle()
    UsbOutputControls(state,model::setUsbOutput)
}

@Composable
internal fun UsbOutputControls(state: UsbSettingsUi, change: (Boolean, Boolean) -> Unit) {
    var warning by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end=12.dp)) {
                Text(if(state.verified) "USB DAC bit-perfect" else "USB DAC · Mixer trực tiếp",fontSize=16.sp)
                Text("Android 14+ · cần Source Direct và bù mức DSD 0 dB",fontSize=13.sp)
            }
            Switch(checked=state.requested,enabled=state.available || state.requested,
                onCheckedChange={ enabled ->
                    if(enabled && !state.warningAccepted) warning=true else change(enabled,false)
                },modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).semantics { contentDescription="Chế độ mixer USB DAC" })
        }
        if(state.connected) Text(if(state.verified) "Bit-perfect USB" else "USB · Mixer hệ thống",fontSize=14.sp)
        Text(state.reason,fontSize=13.sp)
        Text("Không đổi tần số để ép khớp. Định dạng và tần số được đo trong Xuất log trình phát. Bật công tắc chưa đồng nghĩa đường ra đã được xác nhận.",fontSize=13.sp)
    }
    if(warning) AlertDialog(onDismissRequest={warning=false},title={Text("Kiểm tra âm lượng DAC")},
        text={Text("Hạ âm lượng DAC/ampli trước, nút âm lượng điện thoại có thể không còn tác dụng")},
        confirmButton={TextButton(onClick={warning=false;change(true,true)},modifier=Modifier.heightIn(min=48.dp)){Text("Đã hạ âm lượng, bật")}},
        dismissButton={TextButton(onClick={warning=false},modifier=Modifier.heightIn(min=48.dp)){Text("Huỷ")}})
}
