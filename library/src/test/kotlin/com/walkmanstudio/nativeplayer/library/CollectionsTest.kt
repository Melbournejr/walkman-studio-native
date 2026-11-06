// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.library

import androidx.paging.PagingSource
import androidx.room.Room
import com.walkmanstudio.nativeplayer.core.naturalSortKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CollectionsTest {
    private lateinit var db: LibraryDatabase
    @Before fun setup() = runBlocking(Dispatchers.IO) {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LibraryDatabase::class.java).build()
        db.libraryDao().upsertRoot(LibraryRootEntity("r", "content://test/tree/r", "Music", 1))
    }
    @After fun close() { db.close() }
    private fun track(i: Int, path: String = "album/track$i.flac", album: String = "Album", disc: Int = 1, number: Int = i) = TrackEntity(
        id = i.toLong(), rootId = "r", documentId = "doc$i", uri = "content://test/tree/r/document/$i", parentId = "p", name = path.substringAfterLast('/'), relativePath = path,
        mimeType = "audio/flac", size = 1000, lastModified = 1, naturalSortKey = naturalSortKey(path.substringAfterLast('/')), generation = 1,
        title = "Bài $i", artist = "Nghệ sĩ", album = album, discSort = disc, trackSort = number, durationMs = 60000)
    @Test fun folderNavigationDistinguishesLiteralPrefixAndDirectChildren() = runBlocking(Dispatchers.IO) {
        db.libraryDao().upsertTracks(listOf(track(1, "A%_x/song.flac"),track(2,"A%_x/child/song.flac"),track(3,"AB_x/other.flac"),track(4,"root.flac")))
        val c = db.collectionDao()
        assertEquals(listOf("A%_x", "AB_x"), c.folders("r", "").first().map { it.path })
        assertEquals(listOf("A%_x/child"), c.folders("r", "A%_x/").first().map { it.path })
        assertEquals(listOf(1L), c.queue(TrackFilter("r", "A%_x", recursive = false)).map { it.id })
        assertEquals(listOf(1L, 2L), c.queue(TrackFilter("r", "A%_x")).map { it.id })
        assertEquals(listOf(4L), c.queue(TrackFilter("r", "", recursive = false)).map { it.id })
        assertEquals(2L, c.summary(TrackFilter("r", "A%_x")).first().count)
    }
    @Test fun keysetTraversesWholeSelectionBeyondLoadedWindowWithoutDuplicates() = runBlocking(Dispatchers.IO) {
        db.libraryDao().upsertTracks((1..1201).map { track(it) })
        val c = db.collectionDao()
        for (filter in listOf(TrackFilter(rootId = "r"), TrackFilter(rootId = "r", shuffleSeed = 3791))) {
            val seen = mutableListOf<Long>(); var anchor: TrackEntity? = null
            while (true) {
                val rows = c.queue(filter, anchor, after = anchor != null, limit = 200)
                if (rows.isEmpty()) break
                assertTrue(rows.size <= 200); seen.addAll(rows.map { it.id }); anchor = rows.last()
            }
            assertEquals(1201, seen.size); assertEquals((1L..1201L).toSet(), seen.toSet())
            if (filter.shuffleSeed != null) assertNotEquals(seen.sorted(), seen)
        }
    }
    @Test fun playlistKeepsInsertionOrderIgnoresDuplicateAndCascadesDeletedTracks() = runBlocking(Dispatchers.IO) {
        db.libraryDao().upsertTracks((1..3).map { track(it) }); val c = db.collectionDao()
        val id = c.insertPlaylist(PlaylistEntity(name = "A", createdAt = 1))
        listOf(3L,1L,2L,1L).forEach { c.add(id,it) }
        val filter = TrackFilter(playlist = id)
        assertEquals(listOf(3L,1L,2L), c.queue(filter).map { it.id })
        assertEquals(listOf(2L), c.queue(filter, db.libraryDao().getTrackById(1), after = true).map { it.id })
        c.remove(id,1); assertEquals(listOf(3L,2L), c.queue(filter).map { it.id })
        db.openHelper.writableDatabase.execSQL("DELETE FROM tracks WHERE id = 3")
        assertEquals(listOf(2L), c.queue(filter).map { it.id })
        c.deletePlaylist(id); assertTrue(c.queue(filter).isEmpty())
    }
    @Test fun favoritesAndRealTagSearchUseSameSelectionAndSummary() = runBlocking(Dispatchers.IO) {
        db.libraryDao().upsertTracks((1..3).map { track(it) }); val c = db.collectionDao()
        db.libraryDao().toggleFavorite(2)
        val f = TrackFilter(favorites = true, query = "Bài 2")
        assertEquals(listOf(2L), c.queue(f).map { it.id }); assertEquals(60000L, c.summary(f).first().duration)
        val page = c.paging(f).load(PagingSource.LoadParams.Refresh(null, 80, false)) as PagingSource.LoadResult.Page
        assertEquals(2L, (page.data.single() as TrackEntity).id)
        db.libraryDao().toggleFavorite(2); assertEquals(0L,c.summary(f).first().count)
        assertTrue(c.queue(TrackFilter(query = "%")).isEmpty())
    }
    @Test fun selectionRoundTripDoesNotTurnFileNamesIntoSql() {
        val filter = TrackFilter("r", "a' OR 1=1 %_/", false, "'Album", "A", true, 99, "Bài %_", 123)
        assertEquals(filter, TrackFilter.decode(filter.encode())); assertNull(TrackFilter.decode("broken"))
        assertNull(TrackFilter.decode("x".repeat(9000)))
    }
}
