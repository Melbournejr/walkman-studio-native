// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Walkman Studio Native contributors
package com.walkmanstudio.nativeplayer.library

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.walkmanstudio.nativeplayer.core.LibraryScanner
import com.walkmanstudio.nativeplayer.core.SourceRoot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Ordinary bounded worker, not a foreground service and not exempt from Android16 job quotas. */
class ScanWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val rootId = inputData.getString(KEY_ROOT) ?: return@withContext Result.failure()
        val database = LibraryDatabase.getInstance(applicationContext)
        val dao = database.libraryDao()
        val root = dao.getRoot(rootId) ?: return@withContext Result.failure()
        val workId = id.toString()
        val store = RoomScanStore(database, workId)
        var lastProgressAt = 0L
        try {
            val result = LibraryScanner(SafFileSource(applicationContext.contentResolver), store).scan(
                SourceRoot(root.id, root.uri, root.displayName),
                force = inputData.getBoolean(KEY_FORCE, false),
            ) { progress ->
                val now = SystemClock.elapsedRealtime()
                // Batch progress DB writes rather than changing WorkManager Data for every document.
                if (lastProgressAt == 0L || now - lastProgressAt >= 500L) {
                    val lease = dao.getScanState(root.id)
                    if (lease?.workId == workId) store.progress(root.id, lease.generation, progress)
                    setProgress(workDataOf(
                        "visited" to progress.visited, "audioFiles" to progress.audioFiles,
                        "changed" to progress.changed,
                    ))
                    lastProgressAt = now
                }
            }
            MetadataWorker.enqueue(applicationContext, root.id)
            Result.success(workDataOf(
                "visited" to result.progress.visited, "audioFiles" to result.progress.audioFiles,
                "changed" to result.progress.changed, "removed" to result.progress.removed,
            ))
        } catch (cancelled: CancellationException) {
            // The core scanner records interruption without pruning, even when this coroutine is cancelled.
            throw cancelled
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                database.withTransaction {
                    val state = dao.getScanState(rootId)
                    if (dao.getRoot(rootId) != null && state?.workId == workId) {
                        val permissionLost = failure is SecurityException
                        if (permissionLost) dao.setPermissionLost(rootId, true)
                        dao.upsertScanState(state.copy(
                            state = "FAILED",
                            message = if (permissionLost) "Quyền đọc thư mục đã hết; hãy chọn lại thư mục."
                            else "Chưa quét xong: ${failure.message ?: failure.javaClass.simpleName}",
                        ))
                    }
                }
            }
            Result.failure(workDataOf("error" to (failure.message ?: failure.javaClass.simpleName).take(1000)))
        }
    }

    companion object {
        const val KEY_ROOT = "rootId"
        const val KEY_FORCE = "force"
        const val GLOBAL_TAG = "walkman-library-scan"
        fun rootTag(rootId: String): String = "walkman-root:$rootId"
        fun uniqueName(rootId: String): String = "walkman-scan:$rootId"
    }
}
