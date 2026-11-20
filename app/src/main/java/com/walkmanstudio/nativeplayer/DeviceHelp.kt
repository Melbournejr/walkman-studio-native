// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
fun DeviceHelp(dismiss: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("device-help", 0) }
    val exempt = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    fun open(intent: Intent) {
        try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: android.content.ActivityNotFoundException) { Toast.makeText(context, "Hãy mở Cài đặt và tìm tên ứng dụng.", Toast.LENGTH_LONG).show() }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Phát nền trên Xiaomi / HyperOS") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Có thể bỏ qua và mở lại hướng dẫn bằng nút ?. Tên menu phụ thuộc bản HyperOS; ứng dụng không tự bật các thiết lập này.")
            Text("Trạng thái Android: " + if (exempt) "đã được miễn tối ưu pin." else "chưa được miễn tối ưu pin.")
            Text("1. Cài đặt → Ứng dụng → Quản lý ứng dụng → Walkman Studio Native → Tiết kiệm pin → Không hạn chế.")
            Text("2. Trong cài đặt ứng dụng/Quyền, tìm Tự khởi động hoặc Tự khởi động nền và bật cho ứng dụng.")
            Text("3. Mở đa nhiệm, mở menu thẻ ứng dụng và chọn Khóa nếu máy cung cấp.")
            listOf("unrestricted" to "Tôi đã chọn Không hạn chế", "autostart" to "Tôi đã bật Tự khởi động", "locked" to "Tôi đã khóa thẻ đa nhiệm").forEach { (key, label) ->
                var confirmed by remember(key) { mutableStateOf(preferences.getBoolean(key, false)) }
                Row { Checkbox(checked = confirmed, modifier = Modifier.semantics { contentDescription = label + ", bạn tự xác nhận" }, onCheckedChange = { confirmed = it; preferences.edit().putBoolean(key, it).apply() }); Text(label + " (bạn tự xác nhận)") }
            }
            TextButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Mở cài đặt ứng dụng") }
            TextButton(onClick = { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("Mở danh sách tối ưu pin Android") }
            Text("Không có API Android chuẩn để xác nhận ba mục HyperOS trên. Không cần cấp quyền truy cập mọi tệp. Các bước này không bảo đảm chống hệ thống kill; Dừng hoặc Buộc dừng vẫn phải dừng ứng dụng.")
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Đóng / Bỏ qua") } })
}
