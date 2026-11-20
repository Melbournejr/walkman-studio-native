// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Walkman Studio Native contributors
package com.walkmanstudio.nativeplayer.library

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.paging.PagingSource
import com.walkmanstudio.nativeplayer.core.FileSource
import com.walkmanstudio.nativeplayer.core.LibraryScanner
import com.walkmanstudio.nativeplayer.core.ScanProgress
import com.walkmanstudio.nativeplayer.core.ScanResult
import com.walkmanstudio.nativeplayer.core.SourceDocument
import com.walkmanstudio.nativeplayer.core.SourceHandle
import com.walkmanstudio.nativeplayer.core.SourceRoot
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * Integration tests execute the production scanner and Room adapter, with a
 * generated FileSource. This is not a SAF DocumentsProvider or device benchmark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomScanStoreTest {
    private lateinit var database: LibraryDatabase
    private lateinit var databaseName: String
    private val root = SourceRoot("test-root", "content://test/tree/root", "Nhạc kiểm thử")

    @Before
    fun createDatabase() = runBlocking(Dispatchers.IO) {
        databaseName = "scan-test-${UUID.randomUUID()}.db"
        database = Room.databaseBuilder(
            RuntimeEnvironment.getApplication(), LibraryDatabase::class.java, databaseName,
        ).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        database.libraryDao().upsertRoot(
            LibraryRootEntity(root.id, root.uri, root.displayName, System.currentTimeMillis()),
        )
    }

    @After
    fun closeDatabase() {
        database.close()
        RuntimeEnvironment.getApplication().deleteDatabase(databaseName)
    }

    @Test
    fun incrementalScanChangesOnlyModifiedRecordsAndKeepsNumericIds() = runBlocking(Dispatchers.IO) {
        val source = GeneratedSource(37)
        val first = scan(source)
        assertEquals(37L, first.progress.changed)
        assertEquals(37L, database.libraryDao().countTracks())
        val original = database.libraryDao().getTrack(root.id, "f-10")!!

        val unchanged = scan(source)
        assertEquals(0L, unchanged.progress.changed)
        assertEquals(37L, unchanged.progress.unchanged)
        source.changedIndex = 10
        val changed = scan(source)
        assertEquals(1L, changed.progress.changed)
        assertEquals(36L, changed.progress.unchanged)
        val modified = database.libraryDao().getTrack(root.id, "f-10")!!
        assertEquals(original.id, modified.id)
        assertEquals(original.size!! + 1, modified.size)
    }

    @Test
    fun completedTraversalPrunesOnlyMissingRecords() = runBlocking(Dispatchers.IO) {
        scan(GeneratedSource(1000))
        val result = scan(GeneratedSource(750))
        assertEquals(250L, result.progress.removed)
        assertEquals(750L, database.libraryDao().countTracks())
        assertNotNull(database.libraryDao().getTrack(root.id, "f-749"))
        assertNull(database.libraryDao().getTrack(root.id, "f-750"))
    }

    @Test
    fun cancelledTraversalNeverPrunesPreviouslyIndexedFiles() = runBlocking(Dispatchers.IO) {
        scan(GeneratedSource(1000))
        val source = GeneratedSource(1000).apply {
            failureAt = 600
            failure = CancellationException("cancel fixture")
        }
        expectFailure<CancellationException> { scan(source) }
        assertEquals(1000L, database.libraryDao().countTracks())
        assertNotNull(database.libraryDao().getTrack(root.id, "f-999"))
        // A normal retry can complete after the failed generation.
        val retry = scan(GeneratedSource(1000))
        assertEquals(0L, retry.progress.changed)
        assertEquals(1000L, retry.progress.unchanged)
    }

    @Test
    fun providerReadFailureAndPermissionLossNeverPrune() = runBlocking(Dispatchers.IO) {
        scan(GeneratedSource(1000))
        for (problem in listOf(IOException("provider disconnected"), SecurityException("grant revoked"))) {
            val source = GeneratedSource(1000).apply { failureAt = 600; failure = problem }
            try {
                scan(source)
                throw AssertionError("Expected failure")
            } catch (actual: Exception) {
                assertEquals(problem.javaClass, actual.javaClass)
            }
            assertEquals(1000L, database.libraryDao().countTracks())
        }
    }

    @Test
    fun unavailableSizeOrModifiedTimeIsConservativelyReprocessed() = runBlocking(Dispatchers.IO) {
        val source = GeneratedSource(31).apply { unknownMetadata = true }
        assertEquals(31L, scan(source).progress.changed)
        assertEquals(31L, scan(source).progress.changed)
        assertEquals(31L, database.libraryDao().countTracks())
    }

    @Test
    fun forcedRescanTouchesEveryCandidateWithoutDuplicatingRows() = runBlocking(Dispatchers.IO) {
        val source = GeneratedSource(31)
        scan(source)
        val result = LibraryScanner(source, RoomScanStore(database)).scan(root, force = true)
        assertEquals(31L, result.progress.changed)
        assertEquals(31L, database.libraryDao().countTracks())
    }

    @Test
    fun deletingRootDuringTraversalCannotResurrectIndex() = runBlocking(Dispatchers.IO) {
        scan(GeneratedSource(1000))
        var deleted = false
        val source = GeneratedSource(1000)
        try {
            LibraryScanner(source, RoomScanStore(database)).scan(root) { progress ->
                if (!deleted && progress.audioFiles >= 256) {
                    database.libraryDao().deleteRoot(root.id)
                    deleted = true
                }
            }
            throw AssertionError("Deleted root must invalidate the current scan")
        } catch (_: CancellationException) {
            // Store refuses any stale work before writing another batch.
        }
        assertTrue(deleted)
        assertNull(database.libraryDao().getRoot(root.id))
        assertEquals(0L, database.libraryDao().countTracks())
    }

    @Test
    fun newerGenerationRejectsLateOldWorkerCompletion() = runBlocking(Dispatchers.IO) {
        val store = RoomScanStore(database)
        val old = store.begin(root)
        val current = store.begin(root)
        assertTrue(old != current)
        expectFailure<CancellationException> { store.complete(root.id, old, ScanProgress()) }
        assertEquals(0L, store.complete(root.id, current, ScanProgress()))
        assertNotNull(database.libraryDao().getRoot(root.id)?.lastScanAt)
    }

    @Test
    fun scanDoesNotReadAudioPayloadIncludingFiveHundredMegabyteDsd() = runBlocking(Dispatchers.IO) {
        val source = GeneratedSource(100)
        val result = scan(source)
        assertEquals(100L, result.progress.audioFiles)
        assertEquals(0, source.openCalls)
        assertEquals(500L * 1024 * 1024, database.libraryDao().getTrack(root.id, "f-0")!!.size)
    }

    @Test
    fun pagedQueryUsesNaturalOrderAndSearchTreatsWildcardsAsLiteralText() = runBlocking(Dispatchers.IO) {
        scan(GeneratedSource(37))
        val page = database.libraryDao().pagingTracks(root.id, "").load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false),
        )
        assertTrue(page is PagingSource.LoadResult.Page<*, *>)
        val rows = (page as PagingSource.LoadResult.Page<Int, TrackEntity>).data
        assertEquals((0 until 20).map { "f-$it" }, rows.map { it.documentId })

        val original = database.libraryDao().getTrack(root.id, "f-0")!!
        database.libraryDao().upsertTracks(listOf(original.copy(name = "100%_literal.flac")))
        for (query in listOf("%", "_", "%_literal")) {
            val result = database.libraryDao().pagingTracks(root.id, query).load(
                PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false),
            )
            assertTrue(result is PagingSource.LoadResult.Page<*, *>)
            assertEquals(listOf("f-0"), (result as PagingSource.LoadResult.Page<Int, TrackEntity>).data.map { it.documentId })
        }
        val absentRoot = database.libraryDao().pagingTracks("not-granted-root", "").load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false),
        )
        assertTrue(absentRoot is PagingSource.LoadResult.Page<*, *>)
        assertEquals(0, (absentRoot as PagingSource.LoadResult.Page<Int, TrackEntity>).data.size)
    }

    @Test
    fun benchmarkOneHundredThousandFilesWithProductionScannerAndFileBackedRoom() = runBlocking(Dispatchers.IO) {
        val source = GeneratedSource(100_000)
        val first = timed { scan(source) }
        assertEquals(100_000L, first.result.progress.changed)
        assertEquals(100_000L, database.libraryDao().countTracks())
        val unchanged = timed { scan(source) }
        assertEquals(0L, unchanged.result.progress.changed)
        assertEquals(100_000L, unchanged.result.progress.unchanged)
        source.changeOnePercent = true
        val changed = timed { scan(source) }
        assertEquals(1000L, changed.result.progress.changed)
        assertEquals(99_000L, changed.result.progress.unchanged)
        assertEquals(100_000L, database.libraryDao().countTracks())
        assertEquals(0, source.openCalls)

        val pageStart = System.nanoTime()
        val firstPage = database.libraryDao().pagingTracks(root.id, "").load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 80, placeholdersEnabled = false),
        )
        val firstPageNs = System.nanoTime() - pageStart
        assertTrue(firstPage is PagingSource.LoadResult.Page<*, *>)
        assertEquals(80, (firstPage as PagingSource.LoadResult.Page<Int, TrackEntity>).data.size)
        val plan = database.openHelper.readableDatabase.query(
            "EXPLAIN QUERY PLAN SELECT * FROM tracks WHERE rootId = ? ORDER BY discSort, trackSort, naturalSortKey, name, documentId, id LIMIT 80",
            arrayOf(root.id),
        ).use { cursor ->
            val parts = mutableListOf<String>()
            while (cursor.moveToNext()) parts.add(cursor.getString(3))
            parts.joinToString("; ")
        }
        assertTrue("Expected root-sort index: $plan", plan.contains("INDEX"))
        assertTrue("Unexpected temporary sorting: $plan", !plan.contains("TEMP B-TREE"))

        val runtime = Runtime.getRuntime()
        val report = """
            {
              "benchmark": "production LibraryScanner + RoomScanStore + file-backed Room",
              "environment": "Robolectric 4.16.1 API 35 / native SQLite / host JVM",
              "source": "Generated streaming FileSource; no SAF or audio-byte IO",
              "notVerified": ["Xiaomi 14 Pro", "Android 16 HyperOS", "real SAF provider latency", "real 100000-file enumeration"],
              "files": 100000,
              "directories": 100,
              "batchSize": 256,
              "journalMode": "WAL",
              "timingSamplesPerScenario": 1,
              "firstScanMs": ${milliseconds(first.elapsedNs)},
              "unchangedScanMs": ${milliseconds(unchanged.elapsedNs)},
              "onePercentChangedScanMs": ${milliseconds(changed.elapsedNs)},
              "firstPage80RowsMs": ${milliseconds(firstPageNs)},
              "rootPageQueryPlan": "${plan.replace("\\", "\\\\").replace("\"", "\\\"")}",
              "firstScanChanged": ${first.result.progress.changed},
              "unchangedScanChanged": ${unchanged.result.progress.changed},
              "onePercentChangedCount": ${changed.result.progress.changed},
              "audioPayloadOpenCalls": ${source.openCalls},
              "hostAvailableProcessors": ${runtime.availableProcessors()},
              "hostMaxHeapBytes": ${runtime.maxMemory()},
              "heapUsedAfterBytes": ${runtime.totalMemory() - runtime.freeMemory()},
              "databaseBytesIncludingWal": ${databaseBytes()},
              "notes": "Host simulation only. Includes generated-source enumeration and SQLite writes; excludes SAF/Binder latency, tag reads and device storage. Unchanged scans still enumerate and update generation markers. Single samples, no statistical claim."
            }
        """.trimIndent() + "\n"
        val destination = File(System.getProperty("walkman.benchmark.output") ?: "build/reports/benchmark-room-100k.json")
        destination.parentFile?.mkdirs()
        destination.writeText(report)
        println("WALKMAN_BENCHMARK ${destination.absolutePath}\n$report")
    }

    @Test
    fun metadataSurvivesUnchangedScanAndIsInvalidatedWithoutErasingArtist() = runBlocking(Dispatchers.IO) {
        val source = GeneratedSource(37)
        scan(source)
        val original = database.libraryDao().getTrack(root.id, "f-10")!!
        database.libraryDao().upsertTracks(listOf(original.copy(title = "Hello", artist = "Adele", metadataVersion = 1)))
        scan(source)
        assertEquals(1, database.libraryDao().getTrackById(original.id)!!.metadataVersion)
        source.changedIndex = 10
        scan(source)
        val changed = database.libraryDao().getTrackById(original.id)!!
        assertEquals("Adele", changed.artist)
        assertEquals(0, changed.metadataVersion)
        assertEquals(original.id, changed.id)
    }

    @Test
    fun discTrackThenNaturalOrderAlsoDrivesKeysetQueueWindow() = runBlocking(Dispatchers.IO) {
        scan(GeneratedSource(4))
        val rows = (0..3).map { database.libraryDao().getTrack(root.id, "f-$it")!! }
        database.libraryDao().upsertTracks(listOf(
            rows[0].copy(discSort = 2, trackSort = 1), rows[1].copy(discSort = 1, trackSort = 10),
            rows[2].copy(discSort = 1, trackSort = 2), rows[3].copy(discSort = 1, trackSort = 2),
        ))
        assertEquals(listOf("f-2", "f-3", "f-1", "f-0"), database.libraryDao().catalogPage(20, 0).map { it.documentId })
        val anchor = database.libraryDao().getTrack(root.id, "f-3")!!
        assertEquals(listOf("f-3", "f-1", "f-0"), database.libraryDao().playbackWindow(root.id, anchor, 20).map { it.documentId })
    }

    private suspend fun scan(source: GeneratedSource): ScanResult =
        LibraryScanner(source, RoomScanStore(database)).scan(root)

    private data class TimedScan(val result: ScanResult, val elapsedNs: Long)

    private suspend fun timed(block: suspend () -> ScanResult): TimedScan {
        val start = System.nanoTime()
        val result = block()
        return TimedScan(result, System.nanoTime() - start)
    }

    private fun milliseconds(nanoseconds: Long): String = String.format(Locale.ROOT, "%.3f", nanoseconds / 1_000_000.0)

    private fun databaseBytes(): Long {
        val file = RuntimeEnvironment.getApplication().getDatabasePath(databaseName)
        return listOf(file, File(file.path + "-wal"), File(file.path + "-shm")).sumOf { it.length() }
    }

    private suspend inline fun <reified T : Throwable> expectFailure(crossinline block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected ${T::class.java.name}")
        } catch (failure: Throwable) {
            if (failure !is T) throw failure
        }
    }

    /** No list proportional to the number of files is allocated. */
    private class GeneratedSource(private val count: Int) : FileSource {
        var changedIndex: Int? = null
        var changeOnePercent = false
        var unknownMetadata = false
        var failureAt: Int? = null
        var failure: Exception = IOException("fixture failure")
        var openCalls = 0
        private val filesPerDirectory = 1000
        private val directoryCount = (count + filesPerDirectory - 1) / filesPerDirectory

        override suspend fun root(root: SourceRoot) = SourceDocument(
            "root", root.uri, null, root.displayName, "vnd.android.document/directory", null, null, true,
        )

        override fun children(root: SourceRoot, directoryId: String): Flow<SourceDocument> = flow {
            if (directoryId == "root") {
                for (directory in 0 until directoryCount) emit(
                    SourceDocument(
                        "d-$directory", "content://test/tree/root/document/d-$directory", "root",
                        "Album $directory", "vnd.android.document/directory", null, null, true,
                    ),
                )
            } else {
                val directory = directoryId.removePrefix("d-").toInt()
                val start = directory * filesPerDirectory
                val end = minOf(start + filesPerDirectory, count)
                for (index in start until end) {
                    if (failureAt == index) throw failure
                    val isDsd = index < 100
                    val modified = index == changedIndex || (changeOnePercent && index % 100 == 0)
                    emit(
                        SourceDocument(
                            "f-$index", "content://test/tree/root/document/f-$index", directoryId,
                            "Bài $index.${if (isDsd) "dsf" else "flac"}",
                            if (isDsd) "audio/x-dsf" else "audio/flac",
                            if (unknownMetadata) null else (if (isDsd) 500L * 1024 * 1024 else 8L * 1024 * 1024) + if (modified) 1 else 0,
                            if (unknownMetadata) null else 1_700_000_000_000L + if (modified) 1000 else 0,
                            false,
                        ),
                    )
                }
            }
        }

        override suspend fun open(root: SourceRoot, document: SourceDocument): SourceHandle {
            openCalls++
            throw AssertionError("Stage 1 scanner must not open audio payload")
        }
    }
}
