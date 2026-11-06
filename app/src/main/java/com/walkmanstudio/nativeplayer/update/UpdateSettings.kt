// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.update

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UpdateLaunch(val intent:Intent,val permission:Boolean)
class UpdateViewModel(private val updates:AppUpdates,private val context:Context):ViewModel() {
    val state=updates.store.observe().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),updates.store.read())
    val launches=MutableSharedFlow<UpdateLaunch>(extraBufferCapacity=1)
    val messages=MutableSharedFlow<String>(extraBufferCapacity=1)
    private fun io(action:suspend()->Unit) {viewModelScope.launch(Dispatchers.IO) {
        try {action()}catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){messages.emit("Chưa lưu hoặc xử lý được yêu cầu cập nhật. Hãy thử lại.")}
    }}
    fun activate() {io {updates.activate()}}
    fun auto(enabled:Boolean) {io {updates.settings(auto=enabled)}}
    fun mobile(enabled:Boolean) {io {updates.settings(mobile=enabled)}}
    fun check() {io {if(!updates.checkNow())messages.emit("Vừa kiểm tra hoặc đang tải. Hãy chờ một phút trước khi thử lại.")}}
    fun cancel() {io {updates.cancel()}}
    fun permissionReturned() {if(context.packageManager.canRequestPackageInstalls())install() else messages.tryEmit("Chưa được phép cài cập nhật. Gói đã tải vẫn được giữ trong ứng dụng.")}
    fun install() {viewModelScope.launch {
        try {
            val file=withContext(Dispatchers.IO){updates.installFile()}
            if(!context.packageManager.canRequestPackageInstalls()) {
                launches.emit(UpdateLaunch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")),true))
            } else {
                val uri=FileProvider.getUriForFile(context,context.packageName+".updates",file)
                launches.emit(UpdateLaunch(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),false))
            }
        } catch(cancel:CancellationException){throw cancel}
        catch (_:Exception) {messages.emit("Chưa thể cài gói cập nhật. Hãy kiểm tra lại hoặc tải lại gói.")}
    }
    }
    fun launchError(){messages.tryEmit("Máy chưa mở được trình cài đặt. Gói đã xác minh vẫn được giữ trong ứng dụng.")}
}

@Composable
fun UpdateSettings(model:UpdateViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    var feedback by remember {mutableStateOf("")}
    var permissionLaunch by rememberSaveable {mutableStateOf(false)}
    var notifications by remember {mutableStateOf(Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)}
    val installer=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if(permissionLaunch){permissionLaunch=false;model.permissionReturned()}
    }
    val requestNotifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {notifications=it}
    LaunchedEffect(model) {model.messages.collect {feedback=it}}
    LaunchedEffect(model) {model.launches.collect {event->
        permissionLaunch=event.permission
        try {installer.launch(event.intent)}catch(_:ActivityNotFoundException){permissionLaunch=false;model.launchError()}
        catch(_:SecurityException){permissionLaunch=false;model.launchError()}
    }}
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        HorizontalDivider()
        Text("Cập nhật ứng dụng",style=MaterialTheme.typography.titleMedium)
        Text("Kiểm tra hằng ngày qua GitHub. APK được tải trong ứng dụng, xác minh hash và khóa ký trước khi cài. Android vẫn yêu cầu bạn xác nhận cài đặt.",style=MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("Tự kiểm tra và tải bản mới",Modifier.weight(1f))
            Switch(state.auto,model::auto,Modifier.semantics {contentDescription="Tự kiểm tra và tải bản mới"})
        }
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)){Text("Cho phép dữ liệu di động");Text("Mặc định dùng mạng không tính phí (thường là Wi-Fi).",style=MaterialTheme.typography.bodySmall)}
            Switch(state.mobile,model::mobile,Modifier.semantics {contentDescription="Cho phép cập nhật bằng dữ liệu di động"})
        }
        val status=when(state.status){
            UpdateStatus.IDLE->"Chưa kiểm tra cập nhật."
            UpdateStatus.WAITING_NETWORK->if(state.mobile)"Chờ kết nối mạng để kiểm tra hoặc tiếp tục tải." else "Chờ mạng không tính phí (Wi-Fi) để kiểm tra hoặc tiếp tục tải."
            UpdateStatus.CHECKING->"Đang kiểm tra bản mới…"
            UpdateStatus.DOWNLOADING->"Đang tải ${state.candidate?.versionName.orEmpty()} · ${state.downloaded/1024} / ${(state.candidate?.bytes?:0)/1024} KB"
            UpdateStatus.READY->"${state.candidate?.versionName.orEmpty()} đã sẵn sàng cài đặt."
            UpdateStatus.CURRENT->"Chưa có bản cập nhật tương thích mới hơn."
            UpdateStatus.ERROR->"Chưa cập nhật được. ${state.error}"
            UpdateStatus.CANCELED->"Đã hủy tải. Bấm kiểm tra để thử lại bản này."
        }
        Text(status,Modifier.semantics {contentDescription=status},style=MaterialTheme.typography.bodyMedium)
        if(state.status==UpdateStatus.DOWNLOADING) LinearProgressIndicator(progress={
            ((state.downloaded.toDouble()/(state.candidate?.bytes?:1).coerceAtLeast(1)).coerceIn(0.0,1.0)).toFloat()
        },modifier=Modifier.fillMaxWidth())
        if(state.candidate?.notes?.isNotBlank()==true) Text(state.candidate!!.notes,style=MaterialTheme.typography.bodySmall)
        if(state.ready) Button(onClick=model::install,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Cài bản cập nhật")}
        OutlinedButton(onClick=model::check,enabled=state.status !in setOf(UpdateStatus.CHECKING,UpdateStatus.DOWNLOADING),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Kiểm tra bản mới")}
        if(state.status in setOf(UpdateStatus.WAITING_NETWORK,UpdateStatus.CHECKING,UpdateStatus.DOWNLOADING))
            TextButton(onClick=model::cancel,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Hủy kiểm tra / tải")}
        if(Build.VERSION.SDK_INT>=33 && !notifications) TextButton(onClick={requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Bật thông báo khi tải xong")}
        if(feedback.isNotBlank())Text(feedback,style=MaterialTheme.typography.bodyMedium)
        Text("Chỉ luồng cập nhật dùng mạng; không gửi thư viện nhạc, URI thư mục hoặc thông tin bài hát lên máy chủ.",style=MaterialTheme.typography.bodySmall)
        HorizontalDivider()
    }
}
