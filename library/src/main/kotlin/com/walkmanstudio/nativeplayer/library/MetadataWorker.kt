// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.library

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.walkmanstudio.nativeplayer.core.SourceDocument
import com.walkmanstudio.nativeplayer.core.SourceRoot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

class MetadataWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val rootId = inputData.getString("rootId") ?: return@withContext Result.failure()
        val database = LibraryDatabase.getInstance(applicationContext)
        val dao = database.libraryDao()
        val entity = dao.getRoot(rootId) ?: return@withContext Result.failure()
        val root = SourceRoot(entity.id, entity.uri, entity.displayName)
        val source = SafFileSource(applicationContext.contentResolver)
        val reader = MetadataReader()
        val folders = object : LinkedHashMap<String, String?>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?) = size > 128
        }
        var done = 0L
        val start = System.nanoTime()
        while (System.nanoTime() - start < 8L * 60 * 1_000_000_000) {
            currentCoroutineContext().ensureActive()
            val batch = dao.pendingMetadata(rootId, MetadataReader.VERSION, 128)
            if (batch.isEmpty()) return@withContext Result.success()
            val results = ArrayList<Parsed>(batch.size)
            for (track in batch) {
                currentCoroutineContext().ensureActive()
                try {
                    val document = SourceDocument(track.documentId, track.uri, track.parentId, track.name, track.mimeType, track.size, track.lastModified, false)
                    val tags = source.open(root, document).use { handle -> runInterruptible { reader.read(handle, track.name) } }
                    var key = tags.artwork?.let { ArtworkCache.put(applicationContext, it) }
                    if (key == null && track.parentId != null) {
                        val parent = track.parentId
                        if (!folders.containsKey(parent)) {
                            var cover: SourceDocument? = null; var folder: SourceDocument? = null
                            source.children(root, parent).collect { child ->
                                if (!child.isDirectory && child.name.equals("cover.jpg", true)) cover = child
                                if (!child.isDirectory && child.name.equals("folder.jpg", true)) folder = child
                            }
                            val candidate = cover ?: folder
                            folders[parent] = if (candidate != null && candidate!!.size?.let { it in 1..MetadataReader.MAX_ART_BYTES.toLong() } == true) {
                                source.open(root, candidate!!).use { handle ->
                                    val bytes = ByteArray(candidate!!.size!!.toInt()); var count = 0
                                    while (count < bytes.size) { val n = handle.read(bytes, count, bytes.size - count); if (n <= 0) break; count += n }
                                    if (count == bytes.size) ArtworkCache.put(applicationContext, bytes) else null
                                }
                            } else null
                        }
                        key = folders[parent]
                    }
                    results.add(Parsed(track, tags.copy(artwork = null), key, null))
                } catch (cancel: CancellationException) { throw cancel }
                catch (denied: SecurityException) { dao.setPermissionLost(rootId, true); return@withContext Result.failure() }
                catch (failure: Exception) { results.add(Parsed(track, null, null, failure.javaClass.simpleName)) }
            }
            database.withTransaction {
                val updated = results.mapNotNull { parsed ->
                    val current = dao.getTrackById(parsed.track.id) ?: return@mapNotNull null
                    if (!sameSourceStamp(current, parsed.track)) return@mapNotNull null
                    val tag = parsed.tags
                    if (tag == null) current.copy(metadataVersion = MetadataReader.VERSION, metadataError = parsed.error)
                    else current.copy(title = tag.title, artist = tag.artist, album = tag.album, albumArtist = tag.albumArtist,
                        discNumber = tag.disc, trackNumber = tag.track, durationMs = tag.durationMs,
                        coverKey = parsed.coverKey, trackGain = tag.trackGain, albumGain = tag.albumGain,
                        trackPeak = tag.trackPeak, albumPeak = tag.albumPeak, discSort = tag.disc ?: 1,
                        trackSort = tag.track ?: Int.MAX_VALUE, metadataVersion = MetadataReader.VERSION, metadataError = null)
                }
                if (updated.isNotEmpty()) dao.upsertTracks(updated)
            }
            done += results.size
            setProgress(workDataOf("processed" to done, "pending" to dao.pendingMetadataCount(rootId, MetadataReader.VERSION)))
        }
        Result.retry() // Each completed batch persists; next bounded worker only reads pending rows.
    }
    private data class Parsed(val track: TrackEntity, val tags: MusicTags?, val coverKey: String?, val error: String?)
    companion object {
        fun enqueue(context: Context, rootId: String) {
            val work = OneTimeWorkRequestBuilder<MetadataWorker>().setInputData(workDataOf("rootId" to rootId)).addTag("walkman-metadata").build()
            WorkManager.getInstance(context).enqueueUniqueWork("metadata:$rootId", ExistingWorkPolicy.KEEP, work)
        }
        fun sameSourceStamp(a: TrackEntity, b: TrackEntity) = a.rootId == b.rootId && a.documentId == b.documentId && a.size == b.size && a.lastModified == b.lastModified && a.uri == b.uri && a.name == b.name && a.parentId == b.parentId && a.relativePath == b.relativePath && a.mimeType == b.mimeType
    }
}
