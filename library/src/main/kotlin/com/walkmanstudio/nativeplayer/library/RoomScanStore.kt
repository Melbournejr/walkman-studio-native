// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Walkman Studio Native contributors
package com.walkmanstudio.nativeplayer.library

import androidx.room.withTransaction
import com.walkmanstudio.nativeplayer.core.IndexedFile
import com.walkmanstudio.nativeplayer.core.ScanProgress
import com.walkmanstudio.nativeplayer.core.ScanStore
import com.walkmanstudio.nativeplayer.core.SourceRoot
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException

class ScanInvalidatedException : CancellationException("Scan root was removed or its generation replaced")

/** Only complete() prunes. Each bounded batch is atomic and validates its lease. */
class RoomScanStore(
    private val database: LibraryDatabase,
    private val workId: String? = null,
) : ScanStore {
    private val dao = database.libraryDao()

    override suspend fun begin(root: SourceRoot): Long = database.withTransaction {
        if (dao.getRoot(root.id) == null) throw ScanInvalidatedException()
        // Random lease avoids old-worker collisions even when a removed root is re-added.
        val generation = random.nextLong().and(Long.MAX_VALUE).coerceAtLeast(1L)
        dao.upsertScanState(ScanStateEntity(root.id, generation, "RUNNING", workId))
        generation
    }

    override suspend fun applyBatch(rootId: String, generation: Long, files: List<IndexedFile>, force: Boolean): Int {
        require(files.size <= 512) { "Scanner batches must remain bounded" }
        require(files.all { it.rootId == rootId }) { "Mixed-root scan batch" }
        if (files.isEmpty()) return 0
        return database.withTransaction {
            requireLease(rootId, generation)
            val previous = dao.findBatch(rootId, files.map { it.documentId }).associateBy { it.documentId }
            val modified = ArrayList<TrackEntity>(files.size)
            val touched = ArrayList<String>(files.size)
            for (file in files) {
                val old = previous[file.documentId]
                val changed = force || old == null || file.size == null || file.lastModified == null ||
                    old.size != file.size || old.lastModified != file.lastModified ||
                    old.uri != file.uri || old.parentId != file.parentId || old.name != file.name ||
                    old.relativePath != file.relativePath || old.mimeType != file.mimeType ||
                    old.naturalSortKey != file.naturalSortKey
                if (changed) {
                    val fresh = TrackEntity(
                        id = old?.id ?: 0, rootId = rootId, documentId = file.documentId,
                        uri = file.uri, parentId = file.parentId, name = file.name,
                        relativePath = file.relativePath, mimeType = file.mimeType,
                        size = file.size, lastModified = file.lastModified,
                        naturalSortKey = file.naturalSortKey, generation = generation,
                    )
                    modified.add(old?.copy(uri = fresh.uri, parentId = fresh.parentId, name = fresh.name,
                        relativePath = fresh.relativePath, mimeType = fresh.mimeType, size = fresh.size,
                        lastModified = fresh.lastModified, naturalSortKey = fresh.naturalSortKey,
                        generation = generation, metadataVersion = 0, metadataError = null) ?: fresh)
                } else touched.add(file.documentId)
            }
            if (modified.isNotEmpty()) dao.upsertTracks(modified)
            if (touched.isNotEmpty()) dao.touchBatch(rootId, touched, generation)
            modified.size
        }
    }

    override suspend fun complete(rootId: String, generation: Long, progress: ScanProgress): Long = database.withTransaction {
        val state = requireLease(rootId, generation)
        val removed = dao.prune(rootId, generation).toLong()
        dao.markRootScanned(rootId, System.currentTimeMillis())
        dao.upsertScanState(state.copy(
            state = "SUCCEEDED", visited = progress.visited, audioFiles = progress.audioFiles,
            changed = progress.changed, message = null,
        ))
        removed
    }

    override suspend fun interrupted(rootId: String, generation: Long, reason: String) {
        database.withTransaction {
            val state = dao.getScanState(rootId)
            if (dao.getRoot(rootId) != null && state?.generation == generation) {
                dao.upsertScanState(state.copy(state = "INTERRUPTED", message = reason))
            }
        }
    }

    suspend fun progress(rootId: String, generation: Long, progress: ScanProgress) {
        database.withTransaction {
            val state = requireLease(rootId, generation)
            dao.upsertScanState(state.copy(
                visited = progress.visited, audioFiles = progress.audioFiles, changed = progress.changed,
            ))
        }
    }

    private suspend fun requireLease(rootId: String, generation: Long): ScanStateEntity {
        val state = dao.getScanState(rootId)
        if (dao.getRoot(rootId) == null || state?.generation != generation) throw ScanInvalidatedException()
        return state
    }

    private companion object { val random = SecureRandom() }
}
