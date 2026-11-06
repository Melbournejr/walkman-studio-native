// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.walkmanstudio.nativeplayer.BuildConfig
import com.walkmanstudio.nativeplayer.MainActivity
import com.walkmanstudio.nativeplayer.R
import com.walkmanstudio.nativeplayer.core.update.UpdateArtifact
import com.walkmanstudio.nativeplayer.core.update.UpdateTransfer
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class UpdateStatus { IDLE, WAITING_NETWORK, CHECKING, DOWNLOADING, READY, CURRENT, ERROR, CANCELED }
data class UpdateSnapshot(
    val status: UpdateStatus = UpdateStatus.IDLE,
    val candidate: UpdateArtifact? = null,
    val downloaded: Long = 0,
    val readyVersion: Long = 0,
    val error: String = "",
    val checkedAt: Long = 0,
    val auto: Boolean = true,
    val mobile: Boolean = false,
    val declinedVersion: Long = 0,
) { val ready: Boolean get() = candidate != null && readyVersion == candidate.versionCode }

/** Small update state only. Music/library metadata never enters this store or the HTTP client. */
class UpdateStore(context: Context) {
    private val prefs = context.getSharedPreferences("application-updates", Context.MODE_PRIVATE)
    fun read(): UpdateSnapshot = synchronized(lock) {
        val candidate = prefs.getString("candidate", null)?.let { runCatching { GithubUpdateCatalog.decode(JSONObject(it)) }.getOrNull() }
        UpdateSnapshot(runCatching { UpdateStatus.valueOf(prefs.getString("status", "IDLE")!!) }.getOrDefault(UpdateStatus.IDLE), candidate,
            prefs.getLong("downloaded", 0), prefs.getLong("readyVersion", 0), prefs.getString("error", "").orEmpty(),
            prefs.getLong("checkedAt", 0), prefs.getBoolean("auto", true), prefs.getBoolean("mobile", false), prefs.getLong("declined", 0))
    }
    fun observe() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        prefs.registerOnSharedPreferenceChangeListener(listener); trySend(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    private fun edit(block: SharedPreferences.Editor.() -> Unit) = synchronized(lock) {
        check(prefs.edit().apply(block).commit()) { "Chưa lưu được trạng thái cập nhật." }
    }
    fun preferences(auto: Boolean? = null, mobile: Boolean? = null) = edit {
        if(auto!=null)putBoolean("auto",auto)
        if(mobile!=null)putBoolean("mobile",mobile)
    }
    fun mark(status: UpdateStatus, error: String = "") = edit { putString("status", status.name); putString("error", error.take(250)) }
    fun checked(time: Long) = edit { putLong("checkedAt", time) }
    fun downloading(candidate: UpdateArtifact) = edit {
        putString("candidate", GithubUpdateCatalog.encode(candidate)); putString("status", UpdateStatus.DOWNLOADING.name)
        putLong("downloaded", 0); putLong("readyVersion", 0); putString("error", "")
    }
    fun progress(version: Long, bytes: Long) = synchronized(lock) {
        val s=read();if(s.candidate?.versionCode==version && s.status==UpdateStatus.DOWNLOADING) edit { putLong("downloaded",bytes) }
    }
    fun ready(candidate: UpdateArtifact) = edit {
        putString("candidate",GithubUpdateCatalog.encode(candidate));putLong("readyVersion",candidate.versionCode)
        putLong("downloaded",candidate.bytes);putString("status",UpdateStatus.READY.name);putString("error","")
    }
    fun cancel() = edit { putLong("declined",read().candidate?.versionCode ?: 0);putString("status",UpdateStatus.CANCELED.name);putString("error","") }
    fun clearInstalled(version: Long) = synchronized(lock) {
        if(read().candidate?.versionCode?.let { it<=version }==true) edit {
            remove("candidate");putLong("readyVersion",0);putLong("downloaded",0);putString("status",UpdateStatus.IDLE.name)
        }
    }
    companion object { private val lock=Any() }
}

class UpdateRunner(
    private val store: UpdateStore,
    private val folder: File,
    private val latest: () -> UpdateArtifact?,
    private val transport: UpdateTransport,
    private val files: VerifiedUpdateFiles,
    private val installedVersion: Long,
    private val sdk: Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val notifyReady: () -> Unit = {},
) {
    suspend fun run(manual: Boolean, report: suspend (Long,Long) -> Unit = { _,_-> }) {
        val initial=store.read()
        if(!manual && !initial.auto) return
        if(initial.status in setOf(UpdateStatus.CURRENT,UpdateStatus.READY) && initial.checkedAt>0 && clock()-initial.checkedAt < (if(manual)60_000L else TimeUnit.HOURS.toMillis(24))) {
            if(initial.status==UpdateStatus.WAITING_NETWORK) store.mark(if(initial.ready)UpdateStatus.READY else UpdateStatus.CURRENT)
            return
        }
        var partial:File?=null
        try {
            folder.mkdirs();check(folder.isDirectory)
            store.checked(clock());store.mark(UpdateStatus.CHECKING)
            val candidate=latest()
            currentCoroutineContext().ensureActive()
            if(candidate==null || candidate.versionCode<=installedVersion) {
                store.mark(if(store.read().ready)UpdateStatus.READY else UpdateStatus.CURRENT);return
            }
            require(candidate.minSdk<=sdk) { "Bản cập nhật yêu cầu Android mới hơn." }
            if(!manual && store.read().declinedVersion==candidate.versionCode) {store.mark(UpdateStatus.CANCELED);return}
            val target=files.target(candidate)
            if(target.isFile) {
                try { files.verify(target,candidate);store.ready(candidate);return }
                catch (_:IllegalArgumentException) { check(target.delete()) }
            }
            store.downloading(candidate)
            val pending=File(folder,candidate.fileName+".part")
            partial=pending
            var lastProgress=0L
            transport.open(candidate.url).use { body ->
                require(body.length<0 || body.length==candidate.bytes) { "Kích thước tải không khớp." }
                pending.outputStream().buffered().use { output ->
                    UpdateTransfer.copy(body.stream,output,candidate) { bytes ->
                        val now=clock()
                        if(bytes==candidate.bytes || now-lastProgress>=1000) {
                            store.progress(candidate.versionCode,bytes);report(bytes,candidate.bytes);lastProgress=now
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            files.verify(pending,candidate)
            currentCoroutineContext().ensureActive()
            Files.move(pending.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            store.ready(candidate);notifyReady()
        } catch (cancel:CancellationException) {
            if(store.read().status!=UpdateStatus.CANCELED) store.mark(UpdateStatus.WAITING_NETWORK)
            throw cancel
        } catch (error:Exception) {
            store.mark(UpdateStatus.ERROR, if(error is IOException) "Chưa kết nối được máy chủ cập nhật. Có thể thử lại." else "Gói cập nhật không vượt qua kiểm tra an toàn hoặc không tương thích.")
            throw error
        } finally { partial?.delete() }
    }
}

class AppUpdates(private val context: Context) {
    val store=UpdateStore(context)
    private val folder=File(context.filesDir,"updates")
    private val reader=AndroidArchiveReader(context)
    private val verified=VerifiedUpdateFiles(folder,reader,reader::installed,Build.VERSION.SDK_INT)
    private val transport=GithubUpdateTransport()
    private fun constraints()=Constraints.Builder().setRequiredNetworkType(if(store.read().mobile)NetworkType.CONNECTED else NetworkType.UNMETERED).build()
    fun activate() {
        store.clearInstalled(BuildConfig.VERSION_CODE.toLong())
        folder.listFiles()?.forEach { file ->
            val code=Regex("walkman-update-(\\d+)\\.apk(?:\\.part)?").matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()
            if(code!=null && code<=BuildConfig.VERSION_CODE)file.delete()
        }
        if(!store.read().auto)return
        val periodic=PeriodicWorkRequestBuilder<ApplicationUpdateWorker>(24,TimeUnit.HOURS)
            .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,1,TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.UPDATE,periodic)
        if(System.currentTimeMillis()-store.read().checkedAt>=TimeUnit.HOURS.toMillis(24)) enqueue(false)
    }
    fun settings(auto:Boolean?=null,mobile:Boolean?=null) {
        cancelWork();store.preferences(auto,mobile)
        if(store.read().auto){store.checked(0);activate()}
    }
    fun checkNow():Boolean {
        val state=store.read()
        if(state.status in setOf(UpdateStatus.CHECKING,UpdateStatus.DOWNLOADING) || (state.status !in setOf(UpdateStatus.ERROR,UpdateStatus.CANCELED) && System.currentTimeMillis()-state.checkedAt<60_000L))return false
        enqueue(true);return true
    }
    private fun enqueue(manual:Boolean) {
        if(!store.read().ready)store.mark(UpdateStatus.WAITING_NETWORK)
        val work=OneTimeWorkRequestBuilder<ApplicationUpdateWorker>().setConstraints(constraints())
            .setInputData(workDataOf("manual" to manual)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,1,TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW,ExistingWorkPolicy.KEEP,work)
    }
    private fun cancelWork() {WorkManager.getInstance(context).cancelUniqueWork(NOW);WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)}
    fun cancel() {store.cancel();cancelWork();NotificationManagerCompat.from(context).cancel(NOTIFICATION)}
    suspend fun run(manual:Boolean,report:suspend(Long,Long)->Unit) = UpdateRunner(store,folder,
        {GithubUpdateCatalog(transport).latest()},transport,verified,BuildConfig.VERSION_CODE.toLong(),Build.VERSION.SDK_INT,
        notifyReady=::notifyReady).run(manual,report)
    fun installFile():File {
        val state=store.read();val candidate=requireNotNull(state.candidate)
        require(state.ready) { "Chưa có gói đã xác minh." }
        return verified.target(candidate).also {verified.verify(it,candidate)}
    }
    private fun notifyReady() {
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("app-updates","Cập nhật ứng dụng",NotificationManager.IMPORTANCE_DEFAULT))
        val intent=Intent(context,MainActivity::class.java).putExtra(OPEN_UPDATES,true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending=PendingIntent.getActivity(context,712,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(context,"app-updates").setSmallIcon(R.drawable.ic_update)
            .setContentTitle("Bản cập nhật đã sẵn sàng").setContentText("Chạm để xác nhận cài đặt Walkman Studio Native.")
            .setContentIntent(pending).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(context).notify(NOTIFICATION,notification) } catch (_:SecurityException) { /* Permission can be revoked after the check. Ready state remains in Settings. */ }
    }
    companion object {
        const val PERIODIC="native-app-update-periodic"
        const val NOW="native-app-update-now"
        const val OPEN_UPDATES="open_application_updates"
        private const val NOTIFICATION=712
    }
}

class ApplicationUpdateWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        val updates=AppUpdates(applicationContext)
        return try {
            mutex.withLock { withContext(Dispatchers.IO) { updates.run(inputData.getBoolean("manual",false)) {done,total->setProgress(workDataOf("bytes" to done,"total" to total))} } }
            Result.success()
        } catch(cancel:CancellationException) {throw cancel}
        catch (_:IOException) {
            if(runAttemptCount<3){updates.store.mark(UpdateStatus.WAITING_NETWORK,"Kết nối gián đoạn, sẽ tự thử lại.");Result.retry()}else Result.failure()
        } catch (_:Exception) {Result.failure()}
    }
    companion object {private val mutex=Mutex()}
}
