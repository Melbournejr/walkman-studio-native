// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.core

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LibraryScannerTest {
    private val grant = SourceRoot("grant", "content://fake/tree/root", "Music")

    private fun document(id: String, name: String = "$id.flac", parent: String? = "root",
                         directory: Boolean = false, size: Long? = 100, modified: Long? = 1000) =
        SourceDocument(id, "content://fake/tree/root/document/$id", parent, name,
            if (directory) "vnd.android.document/directory" else "application/octet-stream", size, modified, directory)

    private open inner class FakeSource(var entries: List<SourceDocument>) : FileSource {
        var opened = 0
        var rootDocument = document("root", "Music", null, true)
        var failure: Throwable? = null
        var emitted = 0
        var closedCursors = 0
        var liveCursors = 0
        var maxLiveCursors = 0
        override suspend fun root(root: SourceRoot) = rootDocument
        override fun children(root: SourceRoot, directoryId: String): Flow<SourceDocument> = flow {
            liveCursors++
            maxLiveCursors = maxOf(maxLiveCursors, liveCursors)
            try {
                for (entry in entries) if (entry.parentId == directoryId) {
                    emitted++
                    emit(entry)
                }
                failure?.let { throw it }
            } finally { liveCursors--; closedCursors++ }
        }
        override suspend fun open(root: SourceRoot, document: SourceDocument): SourceHandle {
            opened++
            error("Scanner must not open music bytes")
        }
    }

    private open class MemoryStore : ScanStore {
        data class Entry(val file: IndexedFile, val generation: Long)
        val rows = linkedMapOf<String, Entry>()
        var generation = 0L
        var completed = 0
        var interrupted = 0
        var interruptionReason: String? = null
        var batches = 0
        var maxBatch = 0
        var forcedBatches = 0
        override suspend fun begin(root: SourceRoot) = ++generation
        override suspend fun applyBatch(rootId: String, generation: Long, files: List<IndexedFile>, force: Boolean): Int {
            batches++; maxBatch = maxOf(maxBatch, files.size)
            if (force) forcedBatches++
            var changed = 0
            for (file in files) {
                val previous = rows[file.documentId]?.file
                if (force || previous != file || file.size == null || file.lastModified == null) changed++
                rows[file.documentId] = Entry(file, generation)
            }
            return changed
        }
        override suspend fun complete(rootId: String, generation: Long, progress: ScanProgress): Long {
            completed++
            val missing = rows.filterValues { it.generation != generation }.keys
            missing.forEach { rows.remove(it) }
            return missing.size.toLong()
        }
        override suspend fun interrupted(rootId: String, generation: Long, reason: String) {
            interrupted++; interruptionReason = reason
        }
    }

    @Test fun indexesFileMetadataWithoutReading500MbDsd() = runBlocking {
        val source = FakeSource(listOf(document("a", "Large.dsf", size = 500_000_000)))
        val store = MemoryStore()
        val result = LibraryScanner(source, store).scan(grant)
        assertEquals(1, store.rows.size)
        assertEquals(500_000_000L, store.rows.getValue("a").file.size)
        assertEquals(1L, result.progress.changed)
        assertEquals(0, source.opened)
    }

    @Test fun filtersNonAudioAndPreservesOriginalFilename() = runBlocking {
        val source = FakeSource(listOf(document("a", "Original 02.FLAC"), document("cover", "cover.jpg")))
        val store = MemoryStore()
        val result = LibraryScanner(source, store).scan(grant)
        assertEquals(2L, result.progress.visited)
        assertEquals(1L, result.progress.audioFiles)
        assertEquals("Original 02.FLAC", store.rows.getValue("a").file.name)
    }

    @Test fun unchangedSecondScanOnlyTouchesGeneration() = runBlocking {
        val source = FakeSource(listOf(document("a"), document("b")))
        val store = MemoryStore()
        val scanner = LibraryScanner(source, store)
        scanner.scan(grant)
        val result = scanner.scan(grant)
        assertEquals(0L, result.progress.changed)
        assertEquals(2L, result.progress.unchanged)
        assertEquals(2L, store.rows.getValue("a").generation)
    }

    @Test fun changedSizeReindexesOnlyChangedFile() = runBlocking {
        val source = FakeSource(listOf(document("a"), document("b")))
        val store = MemoryStore(); val scanner = LibraryScanner(source, store)
        scanner.scan(grant)
        source.entries = listOf(document("a", size = 101), document("b"))
        assertEquals(1L, scanner.scan(grant).progress.changed)
    }

    @Test fun changedModificationTimeReindexesFile() = runBlocking {
        val source = FakeSource(listOf(document("a"))); val store = MemoryStore()
        val scanner = LibraryScanner(source, store); scanner.scan(grant)
        source.entries = listOf(document("a", modified = 1001))
        assertEquals(1L, scanner.scan(grant).progress.changed)
    }

    @Test fun renameKeepsStableDocumentIdentity() = runBlocking {
        val source = FakeSource(listOf(document("a"))); val store = MemoryStore()
        val scanner = LibraryScanner(source, store); scanner.scan(grant)
        source.entries = listOf(document("a", "New name.flac"))
        assertEquals(1L, scanner.scan(grant).progress.changed)
        assertEquals(1, store.rows.size)
        assertEquals("New name.flac", store.rows.getValue("a").file.name)
    }

    @Test fun unknownMetadataIsConservativelyChangedEveryScan() = runBlocking {
        val source = FakeSource(listOf(document("a", size = null), document("b", modified = null)))
        val store = MemoryStore(); val scanner = LibraryScanner(source, store)
        scanner.scan(grant)
        assertEquals(2L, scanner.scan(grant).progress.changed)
    }

    @Test fun invalidSizeAndModificationAreNormalizedToUnknown() = runBlocking {
        val source = FakeSource(listOf(document("a", size = -1, modified = 0)))
        val store = MemoryStore(); LibraryScanner(source, store).scan(grant)
        assertNull(store.rows.getValue("a").file.size)
        assertNull(store.rows.getValue("a").file.lastModified)
    }

    @Test fun forceRefreshIncludesUnchangedFiles() = runBlocking {
        val source = FakeSource(listOf(document("a"))); val store = MemoryStore()
        val scanner = LibraryScanner(source, store); scanner.scan(grant)
        assertEquals(1L, scanner.scan(grant, force = true).progress.changed)
        assertTrue(store.forcedBatches > 0)
    }

    @Test fun deletesUnseenRowsOnlyAfterSuccessfulScan() = runBlocking {
        val source = FakeSource(listOf(document("a"), document("b"))); val store = MemoryStore()
        val scanner = LibraryScanner(source, store); scanner.scan(grant)
        source.entries = listOf(document("a"))
        val result = scanner.scan(grant)
        assertEquals(1L, result.progress.removed)
        assertEquals(setOf("a"), store.rows.keys)
    }

    @Test fun providerFailureDoesNotPruneExistingRows() = runBlocking {
        val source = FakeSource(listOf(document("a"), document("b"))); val store = MemoryStore()
        val scanner = LibraryScanner(source, store, 1); scanner.scan(grant)
        source.entries = listOf(document("a")); source.failure = IOException("provider failed")
        try { scanner.scan(grant); fail("expected IOException") } catch (_: IOException) { }
        assertEquals(setOf("a", "b"), store.rows.keys)
        assertEquals(1, store.completed)
        assertEquals(1, store.interrupted)
        assertEquals(0, source.liveCursors)
    }

    @Test fun revokedGrantDoesNotPruneExistingRows() = runBlocking {
        val source = FakeSource(listOf(document("a"))); val store = MemoryStore()
        val scanner = LibraryScanner(source, store); scanner.scan(grant)
        source.failure = SecurityException("grant revoked")
        try { scanner.scan(grant); fail("expected SecurityException") } catch (_: SecurityException) { }
        assertEquals(1, store.rows.size)
        assertEquals("SecurityException", store.interruptionReason)
    }

    @Test fun cancellationClosesCursorAndRunsNonCancellableBookkeeping() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        var cursorClosed = false
        val source = object : FakeSource(emptyList()) {
            override fun children(root: SourceRoot, directoryId: String): Flow<SourceDocument> = flow {
                try { emit(document("a")); started.complete(Unit); hold.await() }
                finally { cursorClosed = true }
            }
        }
        val store = MemoryStore()
        val job = launch { LibraryScanner(source, store, 1).scan(grant) }
        started.await(); job.cancelAndJoin()
        assertTrue(cursorClosed)
        assertEquals(0, store.completed)
        assertEquals("cancelled", store.interruptionReason)
        assertEquals(1, store.rows.size)
    }

    @Test fun invalidRootFailsWithoutPrune() = runBlocking {
        val source = FakeSource(emptyList()); source.rootDocument = document("root", directory = false)
        val store = MemoryStore()
        try { LibraryScanner(source, store).scan(grant); fail("expected IOException") } catch (_: IOException) { }
        assertEquals(0, store.completed)
        assertEquals(1, store.interrupted)
    }

    @Test fun validatesBatchConfigurationAndRootId() {
        val source = FakeSource(emptyList()); val store = MemoryStore()
        assertThrows(IllegalArgumentException::class.java) { LibraryScanner(source, store, 0) }
        assertThrows(IllegalArgumentException::class.java) { LibraryScanner(source, store, 513) }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { LibraryScanner(source, store).scan(grant.copy(id = "")) } }
    }

    @Test fun traversalHandlesNestedPathsAndOnlyOneCursorAtATime() = runBlocking {
        val source = FakeSource(listOf(document("album", "Album", directory = true), document("a", parent = "album")))
        val store = MemoryStore(); val result = LibraryScanner(source, store).scan(grant)
        assertEquals("Album/a.flac", store.rows.getValue("a").file.relativePath)
        assertEquals("album", store.rows.getValue("a").file.parentId)
        assertEquals(2L, result.progress.directories)
        assertEquals(1, source.maxLiveCursors)
    }

    @Test fun guardsCyclesAndHandlesDeepTreesWithoutRecursion() = runBlocking {
        val depth = 2048
        val source = object : FakeSource(emptyList()) {
            override fun children(root: SourceRoot, directoryId: String): Flow<SourceDocument> = flow {
                val level = if (directoryId == "root") 0 else directoryId.toInt()
                if (level < depth) emit(document((level + 1).toString(), "D", directoryId, true))
                else {
                    emit(document("a", "Deep.flac", directoryId))
                    emit(document("root", "Cycle", directoryId, true))
                }
            }
        }
        val store = MemoryStore(); val result = LibraryScanner(source, store).scan(grant)
        assertEquals((depth + 1).toLong(), result.progress.directories)
        assertEquals(1L, result.progress.audioFiles)
        assertEquals(depth, store.rows.getValue("a").file.relativePath.count { it == '/' })
    }

    @Test fun streams100001FilesWithBoundedBackpressureAndNoHardCeiling() = runBlocking {
        var applied = 0L
        var largestBatch = 0
        val count = 100_001
        val source = object : FakeSource(emptyList()) {
            override fun children(root: SourceRoot, directoryId: String): Flow<SourceDocument> = flow {
                for (i in 0 until count) {
                    assertTrue("scanner accumulated more than one batch", i - applied < 256)
                    emit(document(i.toString(), "Track $i.flac"))
                }
            }
        }
        val store = object : ScanStore {
            override suspend fun begin(root: SourceRoot) = 1L
            override suspend fun applyBatch(rootId: String, generation: Long, files: List<IndexedFile>, force: Boolean): Int {
                largestBatch = maxOf(largestBatch, files.size); applied += files.size
                return files.size
            }
            override suspend fun complete(rootId: String, generation: Long, progress: ScanProgress) = 0L
            override suspend fun interrupted(rootId: String, generation: Long, reason: String) = Unit
        }
        val result = LibraryScanner(source, store).scan(grant)
        assertEquals(count.toLong(), applied)
        assertEquals(count.toLong(), result.progress.audioFiles)
        assertEquals(256, largestBatch)
        assertEquals(0, source.opened)
    }

    @Test fun progressReportsFinalCountsAndCompletedScanIsNotInterruptedByUiFailure() = runBlocking {
        val source = FakeSource(listOf(document("a"))); val store = MemoryStore()
        val progress = mutableListOf<ScanProgress>()
        val result = LibraryScanner(source, store).scan(grant) { progress.add(it) }
        assertEquals(result.progress, progress.last())
        assertEquals(1L, progress.last().changed)
        try {
            LibraryScanner(source, store).scan(grant) { if (store.completed > 1) throw IOException("UI callback failed") }
            fail("expected callback exception")
        } catch (_: IOException) { }
        assertEquals(0, store.interrupted)
    }

    @Test fun emptyDirectoryCompletesAndPrunesOldRows() = runBlocking {
        val source = FakeSource(listOf(document("a"))); val store = MemoryStore()
        val scanner = LibraryScanner(source, store); scanner.scan(grant); source.entries = emptyList()
        val result = scanner.scan(grant)
        assertEquals(1L, result.progress.removed)
        assertTrue(store.rows.isEmpty())
    }
}
