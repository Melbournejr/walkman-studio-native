// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.library

import androidx.paging.PagingSource
import androidx.room.*
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

@Entity(tableName = "playlists")
data class PlaylistEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val createdAt: Long)
@Entity(tableName = "playlist_entries", primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("trackId"), Index(value = ["playlistId", "position"])])
data class PlaylistEntry(val playlistId: Long, val trackId: Long, val position: Long)
data class FolderSummary(val path: String, val count: Long, val duration: Long, val coverKey: String?)
data class AlbumSummary(val album: String, val artist: String, val count: Long, val duration: Long, val coverKey: String?)
data class SelectionSummary(val count: Long, val duration: Long)

/** Values are always bound SQL arguments, including literal %/_ in SAF file names. */
data class TrackFilter(val rootId: String? = null, val folder: String? = null, val recursive: Boolean = true,
    val album: String? = null, val albumArtist: String? = null, val favorites: Boolean = false,
    val playlist: Long? = null, val query: String = "", val shuffleSeed: Int? = null) {
    fun encode(): String = JSONObject().apply {
        rootId?.let { put("root", it) }; folder?.let { put("folder", it) }; put("recursive", recursive)
        album?.let { put("album", it) }; albumArtist?.let { put("artist", it) }; put("favorites", favorites)
        playlist?.let { put("playlist", it) }; put("query", query.take(512)); shuffleSeed?.let { put("seed", it) }
    }.toString()
    companion object {
        fun decode(raw: String?): TrackFilter? = try {
            if (raw == null || raw.length > 8192) null else JSONObject(raw).let { j ->
                fun text(k: String) = if (j.has(k)) j.getString(k) else null
                TrackFilter(text("root"), text("folder"), j.optBoolean("recursive", true), text("album"), text("artist"),
                    j.optBoolean("favorites"), if (j.has("playlist")) j.getLong("playlist").takeIf { it > 0 } else null,
                    j.optString("query").take(512), if (j.has("seed")) j.getInt("seed").and(Int.MAX_VALUE) else null)
            }
        } catch (_: Exception) { null }
    }
}

@Dao
abstract class CollectionDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt, id") abstract fun playlists(): Flow<List<PlaylistEntity>>
    @Insert abstract suspend fun insertPlaylist(playlist: PlaylistEntity): Long
    @Query("DELETE FROM playlists WHERE id = :id") abstract suspend fun deletePlaylist(id: Long)
    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlist AND trackId = :track") abstract suspend fun remove(playlist: Long, track: Long)
    @Query("SELECT COALESCE(MAX(position),0) + 1 FROM playlist_entries WHERE playlistId = :playlist") protected abstract suspend fun nextPosition(playlist: Long): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertEntry(entry: PlaylistEntry)
    @Transaction open suspend fun add(playlist: Long, track: Long) { insertEntry(PlaylistEntry(playlist, track, nextPosition(playlist))) }
    @RawQuery(observedEntities = [TrackEntity::class, PlaylistEntry::class]) protected abstract fun page(query: SupportSQLiteQuery): PagingSource<Int, TrackEntity>
    @RawQuery protected abstract suspend fun rows(query: SupportSQLiteQuery): List<TrackEntity>
    @RawQuery(observedEntities = [TrackEntity::class, PlaylistEntry::class]) protected abstract fun summaryQuery(query: SupportSQLiteQuery): Flow<SelectionSummary>
    @Query("SELECT substr(relativePath, 1, length(:prefix) + instr(substr(relativePath,length(:prefix)+1),'/') - 1) AS path, COUNT(*) AS count, COALESCE(SUM(durationMs),0) AS duration, MIN(coverKey) AS coverKey FROM tracks WHERE rootId = :root AND substr(relativePath,1,length(:prefix)) = :prefix AND instr(substr(relativePath,length(:prefix)+1),'/') > 0 GROUP BY path ORDER BY path COLLATE NOCASE")
    abstract fun folders(root: String, prefix: String): Flow<List<FolderSummary>>
    @Query("SELECT album, COALESCE(albumArtist, artist, '') AS artist, COUNT(*) AS count, COALESCE(SUM(durationMs),0) AS duration, MIN(coverKey) AS coverKey FROM tracks WHERE album IS NOT NULL AND album != '' GROUP BY album, COALESCE(albumArtist, artist, '') ORDER BY album COLLATE NOCASE, artist")
    abstract fun albums(): PagingSource<Int, AlbumSummary>

    fun paging(filter: TrackFilter) = page(sql(filter, "t.*", null, 0, true))
    fun summary(filter: TrackFilter) = summaryQuery(sql(filter, "COUNT(*) AS count, COALESCE(SUM(t.durationMs),0) AS duration", null, 0, false))
    suspend fun queue(filter: TrackFilter, anchor: TrackEntity? = null, after: Boolean = false, limit: Int = 200): List<TrackEntity> {
        require(limit in 1..300)
        return rows(sql(filter, "t.*", anchor, limit, true, after))
    }
    private fun sql(f: TrackFilter, fields: String, anchor: TrackEntity?, limit: Int, order: Boolean, after: Boolean = false): SimpleSQLiteQuery {
        val args = mutableListOf<Any>(); val where = mutableListOf<String>()
        val join = if (f.playlist == null) "" else " JOIN playlist_entries e ON e.trackId = t.id"
        fun condition(clause: String, vararg values: Any) { where.add(clause); args.addAll(values) }
        f.rootId?.let { condition("t.rootId = ?", it) }
        f.folder?.let { path ->
            val prefix = if (path.isEmpty()) "" else "$path/"
            condition("substr(t.relativePath,1,?) = ?", prefix.length, prefix)
            if (!f.recursive) condition("instr(substr(t.relativePath,?),'/') = 0", prefix.length + 1)
        }
        f.album?.let { condition("t.album = ?", it) }
        f.albumArtist?.let { condition("COALESCE(t.albumArtist,t.artist,'') = ?", it) }
        if (f.favorites) where.add("t.isFavorite = 1")
        f.playlist?.let { condition("e.playlistId = ?", it) }
        if (f.query.isNotBlank()) {
            val escaped = f.query.trim().take(512).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            val pattern = "%$escaped%"
            condition("(t.name LIKE ? ESCAPE '\\' OR t.title LIKE ? ESCAPE '\\' OR t.artist LIKE ? ESCAPE '\\' OR t.album LIKE ? ESCAPE '\\')", pattern, pattern, pattern, pattern)
        }
        val comparison = if (after) ">" else ">="
        val ordering = when {
            f.shuffleSeed != null -> {
                // Literal is generated from a validated Int, never user SQL. Stable, bounded keyset.
                val expression = "((t.id * 1103515245 + ${f.shuffleSeed.and(Int.MAX_VALUE)}) % 2147483647)"
                anchor?.let { condition("($expression,t.id) $comparison (?,?)", (it.id * 1103515245L + f.shuffleSeed.and(Int.MAX_VALUE)) % 2147483647L, it.id) }
                "$expression,t.id"
            }
            f.playlist != null -> {
                anchor?.let { condition("(e.position,t.id) $comparison ((SELECT position FROM playlist_entries WHERE playlistId = ? AND trackId = ?),?)", f.playlist, it.id, it.id) }
                "e.position,t.id"
            }
            else -> {
                anchor?.let { condition("(t.discSort,t.trackSort,t.naturalSortKey,t.name,t.documentId,t.id) $comparison (?,?,?,?,?,?)", it.discSort,it.trackSort,it.naturalSortKey,it.name,it.documentId,it.id) }
                "t.discSort,t.trackSort,t.naturalSortKey,t.name,t.documentId,t.id"
            }
        }
        var statement = "SELECT $fields FROM tracks t$join" + if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        if (order) statement += " ORDER BY $ordering"
        if (limit > 0) { statement += " LIMIT ?"; args.add(limit) }
        return SimpleSQLiteQuery(statement, args.toTypedArray())
    }
}
