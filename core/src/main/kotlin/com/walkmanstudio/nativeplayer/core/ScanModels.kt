// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Walkman Studio Native contributors
package com.walkmanstudio.nativeplayer.core

data class IndexedFile(
    val rootId: String,
    val documentId: String,
    val uri: String,
    val parentId: String?,
    val name: String,
    val relativePath: String,
    val mimeType: String,
    val size: Long?,
    val lastModified: Long?,
    val naturalSortKey: String,
)

data class ScanProgress(
    val visited: Long = 0,
    val audioFiles: Long = 0,
    val changed: Long = 0,
    val unchanged: Long = 0,
    val directories: Long = 0,
    val removed: Long = 0,
)

data class ScanResult(val generation: Long, val progress: ScanProgress)

interface ScanStore {
    suspend fun begin(root: SourceRoot): Long

    /**
     * Atomically touch every seen file; upsert only new/changed/force records.
     * Unknown size or modification time must be treated conservatively as changed.
     * Return the changed count, in 0..files.size. Do not retain batches in memory.
     */
    suspend fun applyBatch(rootId: String, generation: Long, files: List<IndexedFile>, force: Boolean): Int

    /** Prune unseen records and mark success atomically, only after full traversal. */
    suspend fun complete(rootId: String, generation: Long, progress: ScanProgress): Long

    /** Mark an incomplete generation. Never prune or remove old metadata here. */
    suspend fun interrupted(rootId: String, generation: Long, reason: String)
}
