// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.library

import androidx.room.Room
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MetadataMigrationTest {
    @Test fun migratesAcceptedStage1DatabaseWithoutLosingIdsOrGrantedRoot() = runBlocking(Dispatchers.IO) {
        val app = RuntimeEnvironment.getApplication()
        val name = "migration-${UUID.randomUUID()}.db"
        val schemaPath = "schemas/com.walkmanstudio.nativeplayer.library.LibraryDatabase/1.json"
        val schema = listOf(File(schemaPath), File("library/$schemaPath")).first { it.exists() }
        val database = JSONObject(schema.readText()).getJSONObject("database")
        app.openOrCreateDatabase(name, 0, null).use { db ->
            val entities = database.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = database.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO roots(id,uri,displayName,addedAt,lastScanAt,permissionLost) VALUES('root','content://test/tree/music','Music',1,NULL,0)")
            db.execSQL("INSERT INTO tracks(id,rootId,documentId,uri,parentId,name,relativePath,mimeType,size,lastModified,naturalSortKey,generation) VALUES(777,'root','doc','content://test/tree/music/document/doc','parent','Hello.flac','album/Hello.flac','audio/flac',500000000,55,'hello',123)")
            db.version = 1
        }
        val room = Room.databaseBuilder(app, LibraryDatabase::class.java, name).addMigrations(LibraryDatabase.MIGRATION_1_2, LibraryDatabase.MIGRATION_2_3, LibraryDatabase.MIGRATION_3_4).build()
        try {
            val track = room.libraryDao().getTrackById(777)!!
            assertEquals("doc", track.documentId); assertEquals("Hello.flac", track.name)
            assertEquals(500000000L, track.size); assertEquals(123L, track.generation)
            assertEquals(0, track.metadataVersion); assertNull(track.title)
            assertEquals("content://test/tree/music", room.libraryDao().getRoot("root")!!.uri)
            assertEquals(4, room.openHelper.readableDatabase.version)
        } finally { room.close(); app.deleteDatabase(name) }
    }
    @Test fun migratesStage3TagsAndPersistsFavoritesAndPlaylistAcrossReopen() = runBlocking(Dispatchers.IO) {
        val app = RuntimeEnvironment.getApplication()
        val name = "migration-v2-${UUID.randomUUID()}.db"
        val schemaPath = "schemas/com.walkmanstudio.nativeplayer.library.LibraryDatabase/2.json"
        val schema = listOf(File(schemaPath), File("library/$schemaPath")).first { it.exists() }
        val database = JSONObject(schema.readText()).getJSONObject("database")
        app.openOrCreateDatabase(name, 0, null).use { db ->
            val entities = database.getJSONArray("entities")
            for (n in 0 until entities.length()) {
                val entity = entities.getJSONObject(n); val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = database.getJSONArray("setupQueries")
            for (n in 0 until setup.length()) db.execSQL(setup.getString(n))
            db.execSQL("INSERT INTO roots(id,uri,displayName,addedAt,lastScanAt,permissionLost) VALUES('r','content://test/tree/r','Music',1,NULL,0)")
            db.execSQL("INSERT INTO tracks(id,rootId,documentId,uri,parentId,name,relativePath,mimeType,size,lastModified,naturalSortKey,generation,title,artist,metadataVersion,discSort,trackSort) VALUES(777,'r','doc','content://test/tree/r/document/doc','parent','Hello.flac','album/Hello.flac','audio/flac',500000000,55,'hello',123,'Hello','Adele',1,2,10)")
            db.version = 2
        }
        fun open() = Room.databaseBuilder(app, LibraryDatabase::class.java, name).addMigrations(LibraryDatabase.MIGRATION_2_3, LibraryDatabase.MIGRATION_3_4).build()
        var room = open()
        try {
            val track = room.libraryDao().getTrackById(777)!!
            assertEquals("Adele", track.artist); assertEquals("Hello",track.title)
            assertEquals(2,track.discSort); assertEquals(10,track.trackSort); assertFalse(track.isFavorite)
            room.libraryDao().toggleFavorite(777)
            val playlist = room.collectionDao().insertPlaylist(PlaylistEntity(name = "Cá nhân", createdAt = 1))
            room.collectionDao().add(playlist,777)
            room.close(); room = open()
            assertTrue(room.libraryDao().getTrackById(777)!!.isFavorite)
            assertEquals(listOf(777L),room.collectionDao().queue(TrackFilter(playlist = playlist)).map { it.id })
            assertEquals("Adele",room.libraryDao().getTrackById(777)!!.artist)
            assertEquals(4,room.openHelper.readableDatabase.version)
        } finally { room.close(); app.deleteDatabase(name) }
    }

}
