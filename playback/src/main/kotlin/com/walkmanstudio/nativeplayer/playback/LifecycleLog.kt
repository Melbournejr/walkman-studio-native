// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONObject

/** Serialized disk IO, two bounded files; export is explicit through SAF. No upload. */
class LifecycleLog private constructor(private val context: Context) {
    private val writer = Executors.newSingleThreadExecutor()
    private val current = File(context.filesDir, "playback-events.jsonl")
    private val previous = File(context.filesDir, "playback-events.previous.jsonl")
    fun record(event: String, detail: String = "") {
        val line = JSONObject().put("wallTimeMs", System.currentTimeMillis())
            .put("elapsedMs", SystemClock.elapsedRealtime()).put("pid", Process.myPid())
            .put("event", event.take(80)).put("detail", detail.take(1200)).toString() + "\n"
        writer.execute {
            try {
                if (current.length() + line.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
                    previous.delete()
                    if (!current.renameTo(previous)) current.delete()
                }
                current.appendText(line)
            } catch (_: java.io.IOException) { /* Storage failure must not crash playback. */ }
        }
    }
    fun recordHistoricalExits() {
        if (Build.VERSION.SDK_INT < 30) return
        writer.execute {
            try {
                val preferences = context.getSharedPreferences("exit-log", Context.MODE_PRIVATE)
                val since = preferences.getLong("timestamp", 0)
                val manager = context.getSystemService(ActivityManager::class.java)
                val exits = manager?.getHistoricalProcessExitReasons(context.packageName, 0, 8).orEmpty()
                exits.filter { it.timestamp > since }.reversed().forEach {
                    record("historical_process_exit", "timestamp=${it.timestamp}; reason=${it.reason}; status=${it.status}; observed_on_next_launch=true")
                }
                exits.maxOfOrNull { it.timestamp }?.let { preferences.edit().putLong("timestamp", it).apply() }
            } catch (_: Exception) { record("historical_exit_unavailable") }
        }
    }
    fun export(uri: Uri, completed: (Boolean) -> Unit) {
        require(uri.scheme == "content")
        writer.execute {
            val success = try {
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Không mở được nơi lưu log")
                output.use { out -> for (file in listOf(previous, current)) if (file.exists()) file.inputStream().use { it.copyTo(out, 16 * 1024) } }
                true
            } catch (_: Exception) { false }
            completed(success)
        }
    }
    internal fun flush() = writer.submit { /* Barrier for ordered writes and bounded-log verification. */ }
    companion object {
        const val MAX_BYTES = 256 * 1024L
        @Volatile private var instance: LifecycleLog? = null
        fun get(context: Context): LifecycleLog = instance ?: synchronized(this) {
            instance ?: LifecycleLog(context.applicationContext).also { instance = it }
        }
    }
}
