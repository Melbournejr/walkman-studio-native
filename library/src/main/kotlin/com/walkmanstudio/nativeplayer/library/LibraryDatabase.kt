// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Walkman Studio Native contributors
package com.walkmanstudio.nativeplayer.library

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.ColumnInfo
import androidx.room.RawQuery
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "roots")
data class LibraryRootEntity(
    @PrimaryKey val id: String,
    val uri: String,
    val displayName: String,
    val addedAt: Long,
    val lastScanAt: Long? = null,
    val permissionLost: Boolean = false,
)

@Entity(
    tableName = "tracks",
    foreignKeys = [ForeignKey(
        entity = LibraryRootEntity::class,
        parentColumns = ["id"], childColumns = ["rootId"], onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index(value = ["rootId", "documentId"], unique = true),
        Index(value = ["rootId", "discSort", "trackSort", "naturalSortKey", "name", "documentId"]),
        Index(value = ["discSort", "trackSort", "naturalSortKey", "name", "documentId"]),
        Index(value = ["rootId", "generation"]),
        Index(value = ["rootId", "metadataVersion", "id"]),
        Index(value = ["rootId", "relativePath"]),
        Index(value = ["isFavorite", "discSort", "trackSort", "naturalSortKey", "name", "documentId"]),
        Index(value = ["album", "albumArtist"]),
        Index(value = ["coverKey"]),
    ],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
    val generation: Long,
    @ColumnInfo(defaultValue = "0") val isFavorite: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
    val durationMs: Long? = null,
    val coverKey: String? = null,
    val trackGain: Double? = null,
    val albumGain: Double? = null,
    val trackPeak: Double? = null,
    val albumPeak: Double? = null,
    @ColumnInfo(defaultValue = "0") val metadataVersion: Int = 0,
    val metadataError: String? = null,
    @ColumnInfo(defaultValue = "1") val discSort: Int = 1,
    @ColumnInfo(defaultValue = "2147483647") val trackSort: Int = Int.MAX_VALUE,
)

@Entity(
    tableName = "scan_states",
    foreignKeys = [ForeignKey(
        entity = LibraryRootEntity::class,
        parentColumns = ["id"], childColumns = ["rootId"], onDelete = ForeignKey.CASCADE,
    )],
)
data class ScanStateEntity(
    @PrimaryKey val rootId: String,
    val generation: Long,
    val state: String,
    val workId: String? = null,
    val visited: Long = 0,
    val audioFiles: Long = 0,
    val changed: Long = 0,
    val message: String? = null,
)

@Dao
abstract class LibraryDao {
    @Query("SELECT * FROM roots ORDER BY addedAt, id")
    abstract fun observeRoots(): Flow<List<LibraryRootEntity>>

    @Query("SELECT COUNT(*) FROM tracks")
    abstract fun observeTrackCount(): Flow<Long>

    @Query("SELECT * FROM scan_states")
    abstract fun observeScanStates(): Flow<List<ScanStateEntity>>

    @Query("SELECT * FROM roots WHERE id = :id")
    abstract suspend fun getRoot(id: String): LibraryRootEntity?

    @Upsert
    abstract suspend fun upsertRoot(root: LibraryRootEntity)

    @Query("DELETE FROM roots WHERE id = :id")
    abstract suspend fun deleteRoot(id: String)

    @Query("UPDATE roots SET permissionLost = :lost WHERE id = :id")
    abstract suspend fun setPermissionLost(id: String, lost: Boolean)

    @Query("UPDATE roots SET lastScanAt = :time, permissionLost = 0 WHERE id = :id")
    abstract suspend fun markRootScanned(id: String, time: Long)

    @Query("SELECT COUNT(*) FROM tracks")
    abstract suspend fun countTracks(): Long

    @Query("SELECT * FROM tracks WHERE rootId = :rootId AND documentId = :documentId")
    abstract suspend fun getTrack(rootId: String, documentId: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id = :id")
    abstract suspend fun getTrackById(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE coverKey = :key LIMIT 1")
    abstract suspend fun coverOwner(key:String):TrackEntity?

    @Query("SELECT * FROM tracks WHERE id = :id")
    abstract fun observeTrack(id: Long): Flow<TrackEntity?>

    @Query("UPDATE tracks SET isFavorite = NOT isFavorite WHERE id = :id")
    abstract suspend fun toggleFavorite(id: Long)


    @Query("SELECT * FROM tracks ORDER BY discSort, trackSort, naturalSortKey, name, documentId, id LIMIT :limit OFFSET :offset")
    abstract suspend fun catalogPage(limit: Int, offset: Long): List<TrackEntity>

    @RawQuery
    protected abstract suspend fun window(query: SupportSQLiteQuery): List<TrackEntity>

    suspend fun playbackWindow(rootId: String?, anchor: TrackEntity, limit: Int): List<TrackEntity> {
        require(limit in 1..300)
        val root = if (rootId == null) "" else "rootId = ? AND "
        val args = mutableListOf<Any>()
        if (rootId != null) args.add(rootId)
        args.addAll(listOf(anchor.discSort, anchor.trackSort, anchor.naturalSortKey, anchor.name, anchor.documentId, anchor.id, limit))
        return window(SimpleSQLiteQuery("SELECT * FROM tracks WHERE ${root}(discSort, trackSort, naturalSortKey, name, documentId, id) >= (?, ?, ?, ?, ?, ?) ORDER BY discSort, trackSort, naturalSortKey, name, documentId, id LIMIT ?", args.toTypedArray()))
    }

    @Query("SELECT * FROM roots")
    abstract suspend fun listRoots(): List<LibraryRootEntity>

    @Query("SELECT * FROM tracks WHERE rootId = :rootId AND metadataVersion < :version ORDER BY metadataVersion, id LIMIT :limit")
    abstract suspend fun pendingMetadata(rootId: String, version: Int, limit: Int): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM tracks WHERE rootId = :rootId AND metadataVersion < :version")
    abstract suspend fun pendingMetadataCount(rootId: String, version: Int): Long

    @Query("SELECT * FROM tracks WHERE rootId = :rootId AND documentId IN (:ids)")
    abstract suspend fun findBatch(rootId: String, ids: List<String>): List<TrackEntity>

    @Upsert
    abstract suspend fun upsertTracks(tracks: List<TrackEntity>)

    @Query("UPDATE tracks SET generation = :generation WHERE rootId = :rootId AND documentId IN (:ids)")
    abstract suspend fun touchBatch(rootId: String, ids: List<String>, generation: Long)

    @Query("DELETE FROM tracks WHERE rootId = :rootId AND generation != :generation")
    abstract suspend fun prune(rootId: String, generation: Long): Int

    @Query("SELECT * FROM scan_states WHERE rootId = :rootId")
    abstract suspend fun getScanState(rootId: String): ScanStateEntity?

    @Upsert
    abstract suspend fun upsertScanState(state: ScanStateEntity)

    @Query("SELECT * FROM tracks ORDER BY discSort, trackSort, naturalSortKey, name, documentId, id")
    protected abstract fun pageAll(): PagingSource<Int, TrackEntity>

    @Query("SELECT * FROM tracks WHERE rootId = :rootId ORDER BY discSort, trackSort, naturalSortKey, name, documentId, id")
    protected abstract fun pageRoot(rootId: String): PagingSource<Int, TrackEntity>

    @Query("SELECT * FROM tracks WHERE name LIKE :pattern ESCAPE '\\' OR title LIKE :pattern ESCAPE '\\' OR artist LIKE :pattern ESCAPE '\\' OR album LIKE :pattern ESCAPE '\\' ORDER BY discSort, trackSort, naturalSortKey, name, documentId, id")
    protected abstract fun pageSearch(pattern: String): PagingSource<Int, TrackEntity>

    @Query("SELECT * FROM tracks WHERE rootId = :rootId AND (name LIKE :pattern ESCAPE '\\' OR title LIKE :pattern ESCAPE '\\' OR artist LIKE :pattern ESCAPE '\\' OR album LIKE :pattern ESCAPE '\\') ORDER BY discSort, trackSort, naturalSortKey, name, documentId, id")
    protected abstract fun pageRootSearch(rootId: String, pattern: String): PagingSource<Int, TrackEntity>

    fun pagingTracks(rootId: String?, query: String): PagingSource<Int, TrackEntity> {
        val term = query.trim()
        if (term.isEmpty()) return if (rootId == null) pageAll() else pageRoot(rootId)
        val escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return if (rootId == null) pageSearch("%$escaped%") else pageRootSearch(rootId, "%$escaped%")
    }
}

@Database(entities = [LibraryRootEntity::class, TrackEntity::class, ScanStateEntity::class, PlaylistEntity::class, PlaylistEntry::class], version = 4, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun collectionDao(): CollectionDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (column in listOf("title", "artist", "album", "albumArtist", "coverKey", "metadataError")) db.execSQL("ALTER TABLE tracks ADD COLUMN $column TEXT")
                for (column in listOf("discNumber", "trackNumber", "durationMs")) db.execSQL("ALTER TABLE tracks ADD COLUMN $column INTEGER")
                for (column in listOf("trackGain", "albumGain", "trackPeak", "albumPeak")) db.execSQL("ALTER TABLE tracks ADD COLUMN $column REAL")
                db.execSQL("ALTER TABLE tracks ADD COLUMN metadataVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN discSort INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE tracks ADD COLUMN trackSort INTEGER NOT NULL DEFAULT 2147483647")
                db.execSQL("DROP INDEX IF EXISTS index_tracks_rootId_naturalSortKey_name_documentId")
                db.execSQL("DROP INDEX IF EXISTS index_tracks_naturalSortKey_name_documentId")
                db.execSQL("CREATE INDEX index_tracks_rootId_discSort_trackSort_naturalSortKey_name_documentId ON tracks(rootId, discSort, trackSort, naturalSortKey, name, documentId)")
                db.execSQL("CREATE INDEX index_tracks_discSort_trackSort_naturalSortKey_name_documentId ON tracks(discSort, trackSort, naturalSortKey, name, documentId)")
                db.execSQL("CREATE INDEX index_tracks_rootId_metadataVersion_id ON tracks(rootId, metadataVersion, id)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX index_tracks_rootId_relativePath ON tracks(rootId, relativePath)")
                db.execSQL("CREATE INDEX index_tracks_isFavorite_discSort_trackSort_naturalSortKey_name_documentId ON tracks(isFavorite, discSort, trackSort, naturalSortKey, name, documentId)")
                db.execSQL("CREATE INDEX index_tracks_album_albumArtist ON tracks(album, albumArtist)")
                db.execSQL("CREATE TABLE IF NOT EXISTS playlists(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS playlist_entries(playlistId INTEGER NOT NULL, trackId INTEGER NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(playlistId, trackId), FOREIGN KEY(playlistId) REFERENCES playlists(id) ON DELETE CASCADE, FOREIGN KEY(trackId) REFERENCES tracks(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX index_playlist_entries_trackId ON playlist_entries(trackId)")
                db.execSQL("CREATE INDEX index_playlist_entries_playlistId_position ON playlist_entries(playlistId, position)")
            }
        }
        val MIGRATION_3_4=object:Migration(3,4){
            override fun migrate(db:SupportSQLiteDatabase){db.execSQL("CREATE INDEX index_tracks_coverKey ON tracks(coverKey)")}
        }
        @Volatile private var instance: LibraryDatabase? = null

        fun getInstance(context: Context): LibraryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, LibraryDatabase::class.java, "walkman-library.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).setJournalMode(JournalMode.WRITE_AHEAD_LOGGING).build().also { instance = it }
        }
    }
}
