// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Walkman Studio Native contributors
package com.walkmanstudio.nativeplayer.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.walkmanstudio.nativeplayer.core.SourceRoot
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ScanStatus(
    val rootId: String,
    val running: Boolean,
    val state: String,
    val visited: Long,
    val audioFiles: Long,
    val changed: Long,
    val message: String? = null,
)

class LibraryRepository(context: Context, private val database: LibraryDatabase) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val dao = database.libraryDao()
    private val workManager = WorkManager.getInstance(this.context)

    val roots: Flow<List<LibraryRootEntity>> = dao.observeRoots()
    val trackCount: Flow<Long> = dao.observeTrackCount()
    val scans: Flow<List<ScanStatus>> = combine(
        roots, dao.observeScanStates(), workManager.getWorkInfosByTagFlow(ScanWorker.GLOBAL_TAG),
    ) { roots, states, infos ->
        val byRoot = states.associateBy { it.rootId }
        roots.map { root ->
            val state = byRoot[root.id]
            val candidates = infos.filter { ScanWorker.rootTag(root.id) in it.tags }
            val info = candidates.firstOrNull { !it.state.isFinished }
                ?: candidates.firstOrNull { it.id.toString() == state?.workId }
            val active = info?.state?.isFinished == false
            val stopReason = info?.stopReason?.takeUnless { it == WorkInfo.STOP_REASON_NOT_STOPPED }
            val message = when {
                root.permissionLost -> "Quyền đọc thư mục đã hết. Hãy chọn lại thư mục."
                info?.state == WorkInfo.State.CANCELLED -> "Đã hủy quét; giữ chỉ mục hiện có."
                stopReason != null -> "${state?.message.orEmpty()}\nWorkManager stopReason=$stopReason".trim()
                else -> state?.message
            }
            ScanStatus(
                rootId = root.id, running = active,
                state = when {
                    active -> info!!.state.name
                    info?.state == WorkInfo.State.CANCELLED -> "CANCELLED"
                    else -> state?.state ?: "NOT_SCANNED"
                },
                visited = state?.visited ?: 0,
                audioFiles = state?.audioFiles ?: 0,
                changed = state?.changed ?: 0,
                message = message,
            )
        }
    }.distinctUntilChanged()

    fun tracks(rootId: String? = null, query: String = ""): Flow<PagingData<TrackEntity>> = Pager(
        config = PagingConfig(pageSize = 80, initialLoadSize = 160, maxSize = 800, enablePlaceholders = false),
        pagingSourceFactory = { dao.pagingTracks(rootId, query) },
    ).flow

    val playlists = database.collectionDao().playlists()
    val albums = Pager(PagingConfig(pageSize = 40, enablePlaceholders = false)) { database.collectionDao().albums() }.flow
    val metadataProgress = workManager.getWorkInfosByTagFlow("walkman-metadata").combine(roots) { infos, _ ->
        val active = infos.filter { !it.state.isFinished }
        if (active.isEmpty()) null else "Đang đọc tag · ${active.sumOf { it.progress.getLong("processed", 0) }} bài trong lượt này"
    }
    fun selection(filter: TrackFilter) = Pager(PagingConfig(pageSize = 80, initialLoadSize = 160, maxSize = 800, enablePlaceholders = false)) { database.collectionDao().paging(filter) }.flow
    fun summary(filter: TrackFilter) = database.collectionDao().summary(filter)
    fun folders(rootId: String, path: String) = database.collectionDao().folders(rootId, if (path.isEmpty()) "" else "$path/")
    suspend fun favorite(track: Long) = dao.toggleFavorite(track)
    suspend fun createPlaylist(name: String): Long {
        require(name.trim().isNotEmpty() && name.length <= 120)
        return database.collectionDao().insertPlaylist(PlaylistEntity(name = name.trim(), createdAt = System.currentTimeMillis()))
    }
    suspend fun addToPlaylist(playlist: Long, track: Long) = database.collectionDao().add(playlist, track)
    suspend fun removeFromPlaylist(playlist: Long, track: Long) = database.collectionDao().remove(playlist, track)
    suspend fun deletePlaylist(playlist: Long) = database.collectionDao().deletePlaylist(playlist)

    suspend fun addTree(uri: Uri): LibraryRootEntity = withContext(Dispatchers.IO) {
        rootMutations.withLock {
            require(uri.scheme == "content" && !uri.authority.isNullOrBlank() && DocumentsContract.isTreeUri(uri)) {
                "Hãy chọn thư mục bằng trình chọn SAF."
            }
            val alreadyGranted = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            var indexed = false
            try {
                val id = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                val source = SourceRoot(id, uri.toString(), "")
                val rootDocument = SafFileSource(resolver).root(source)
                require(rootDocument.isDirectory) { "Nguồn đã chọn không phải thư mục." }
                val entity = database.withTransaction {
                    val previous = dao.getRoot(id)
                    LibraryRootEntity(
                        id = id, uri = uri.toString(), displayName = rootDocument.name,
                        addedAt = previous?.addedAt ?: System.currentTimeMillis(),
                        lastScanAt = previous?.lastScanAt, permissionLost = false,
                    ).also { dao.upsertRoot(it) }
                }
                indexed = true
                rescan(id)
                entity
            } catch (failure: Throwable) {
                if (!alreadyGranted && !indexed) {
                    try { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    catch (_: SecurityException) { /* No retained grant to clean up. */ }
                }
                throw failure
            }
        }
    }

    fun rescan(rootId: String, force: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<ScanWorker>()
            .setInputData(workDataOf(ScanWorker.KEY_ROOT to rootId, ScanWorker.KEY_FORCE to force))
            .addTag(ScanWorker.GLOBAL_TAG)
            .addTag(ScanWorker.rootTag(rootId))
            .build()
        workManager.enqueueUniqueWork(ScanWorker.uniqueName(rootId), ExistingWorkPolicy.KEEP, request)
    }

    fun cancelScan(rootId: String) {
        workManager.cancelUniqueWork("metadata:$rootId")
        workManager.cancelUniqueWork(ScanWorker.uniqueName(rootId))
    }

    fun cancelMetadata() { workManager.cancelAllWorkByTag("walkman-metadata") }

    suspend fun indexMissingMetadata() = withContext(Dispatchers.IO) {
        dao.listRoots().forEach { if (dao.pendingMetadataCount(it.id, MetadataReader.VERSION) > 0) MetadataWorker.enqueue(context, it.id) }
    }

    suspend fun removeRoot(rootId: String) = withContext(Dispatchers.IO) {
        rootMutations.withLock {
            val root = database.withTransaction {
                val existing = dao.getRoot(rootId)
                // Cascade removes index/state. Every old batch checks root + random generation lease.
                dao.deleteRoot(rootId)
                existing
            }
            // Wait for cancellation to be recorded before addTree can enqueue the same unique name.
            workManager.cancelUniqueWork(ScanWorker.uniqueName(rootId)).result.get()
            workManager.cancelUniqueWork("metadata:$rootId").result.get()
            if (root != null) {
                try {
                    resolver.releasePersistableUriPermission(Uri.parse(root.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) { /* Grant may already have been revoked. */ }
            }
        }
    }

    private companion object { val rootMutations = Mutex() }
}
