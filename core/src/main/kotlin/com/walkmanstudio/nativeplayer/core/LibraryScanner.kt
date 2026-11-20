// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Walkman Studio Native contributors
package com.walkmanstudio.nativeplayer.core

import java.io.IOException
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/**
 * Iterative read-only traversal. Memory is O(batchSize + directory count), not
 * O(audio-file count). Exactly one source directory is streamed at a time.
 * The caller runs provider/database I/O off main and serializes scans per root.
 */
class LibraryScanner(
    private val source: FileSource,
    private val store: ScanStore,
    private val batchSize: Int = 256,
) {
    init { require(batchSize in 1..512) { "Batch size must be between 1 and 512" } }

    suspend fun scan(
        root: SourceRoot,
        force: Boolean = false,
        onProgress: suspend (ScanProgress) -> Unit = {},
    ): ScanResult {
        require(root.id.isNotBlank()) { "Root ID must not be blank" }
        val generation = store.begin(root)
        var completed = false
        var visited = 0L
        var audioFiles = 0L
        var changed = 0L
        var unchanged = 0L
        var directories = 0L
        var lastReportedVisit = 0L
        val batch = ArrayList<IndexedFile>(batchSize)

        fun progress(removed: Long = 0L) = ScanProgress(visited, audioFiles, changed, unchanged, directories, removed)

        suspend fun report(forceReport: Boolean = false) {
            if (forceReport || visited - lastReportedVisit >= batchSize) {
                onProgress(progress())
                lastReportedVisit = visited
            }
        }

        suspend fun flush() {
            if (batch.isEmpty()) return
            currentCoroutineContext().ensureActive()
            val modified = store.applyBatch(root.id, generation, batch.toList(), force)
            check(modified in 0..batch.size) { "Invalid changed count from scan store" }
            changed += modified
            unchanged += batch.size - modified
            batch.clear()
            report(forceReport = true)
        }

        try {
            currentCoroutineContext().ensureActive()
            val rootDocument = source.root(root)
            if (!rootDocument.isDirectory || rootDocument.id.isBlank()) {
                throw IOException("The granted source is not a readable directory")
            }
            onProgress(progress())
            val pending = ArrayDeque<Directory>()
            val scheduled = HashSet<String>()
            scheduled.add(rootDocument.id)
            pending.addLast(Directory(rootDocument.id, ""))
            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val directory = pending.removeLast()
                directories++
                source.children(root, directory.id).collect { document ->
                    currentCoroutineContext().ensureActive()
                    if (document.id.isBlank()) throw IOException("Provider returned an empty document ID")
                    visited++
                    val path = if (directory.path.isEmpty()) document.name else "${directory.path}/${document.name}"
                    if (document.isDirectory) {
                        if (scheduled.add(document.id)) pending.addLast(Directory(document.id, path))
                    } else if (AudioFileTypes.isAudioCandidate(document)) {
                        audioFiles++
                        batch.add(IndexedFile(
                            rootId = root.id,
                            documentId = document.id,
                            uri = document.uri,
                            parentId = document.parentId ?: directory.id,
                            name = document.name,
                            relativePath = path,
                            mimeType = document.mimeType,
                            size = document.size?.takeIf { it >= 0L },
                            lastModified = document.lastModified?.takeIf { it > 0L },
                            naturalSortKey = naturalSortKey(document.name),
                        ))
                        if (batch.size >= batchSize) flush()
                    }
                    report()
                }
            }
            flush()
            currentCoroutineContext().ensureActive()
            val removed = store.complete(root.id, generation, progress())
            check(removed >= 0) { "Invalid removed count from scan store" }
            completed = true
            val finalProgress = progress(removed)
            onProgress(finalProgress)
            return ScanResult(generation, finalProgress)
        } catch (failure: Throwable) {
            if (!completed) {
                try {
                    withContext(NonCancellable) {
                        store.interrupted(root.id, generation,
                            if (failure is CancellationException) "cancelled" else failure.javaClass.simpleName)
                    }
                } catch (bookkeepingFailure: Throwable) {
                    failure.addSuppressed(bookkeepingFailure)
                }
            }
            throw failure
        }
    }

    private data class Directory(val id: String, val path: String)
}
