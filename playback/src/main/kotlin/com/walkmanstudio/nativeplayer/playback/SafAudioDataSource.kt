// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.walkmanstudio.nativeplayer.core.SourceDocument
import com.walkmanstudio.nativeplayer.core.SourceHandle
import com.walkmanstudio.nativeplayer.core.SourceRoot
import com.walkmanstudio.nativeplayer.library.LibraryDatabase
import com.walkmanstudio.nativeplayer.library.SafFileSource
import java.io.EOFException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Only indexed numeric IDs are accepted. Never fall through to a network/file DataSource. */
@androidx.annotation.OptIn(UnstableApi::class)
class SafAudioDataSource(private val openTrack: (Long) -> SourceHandle) : BaseDataSource(false) {
    private var handle: SourceHandle? = null
    private var uri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        close()
        val id = parseTrackId(dataSpec.uri)
        transferInitializing(dataSpec)
        val source = openTrack(id)
        try {
            source.seek(dataSpec.position)
            val available = source.size?.let { (it - dataSpec.position).also { count ->
                if (count < 0) throw EOFException("Vị trí đọc vượt kích thước nguồn.")
            } }
            remaining = when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> available?.let { minOf(it, dataSpec.length) } ?: dataSpec.length
                else -> available ?: C.LENGTH_UNSET.toLong()
            }
            handle = source; uri = dataSpec.uri; started = true
            transferStarted(dataSpec)
            return remaining
        } catch (failure: Throwable) { source.close(); throw failure }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val source = handle ?: throw IOException("Nguồn chưa mở.")
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val wanted = minOf(length.toLong(), MAX_READ.toLong(), if (remaining < 0) Long.MAX_VALUE else remaining).toInt()
        val count = source.read(buffer, offset, wanted)
        if (count < 0) {
            if (remaining > 0) throw EOFException("Nguồn kết thúc trước cửa sổ đọc yêu cầu.")
            return C.RESULT_END_OF_INPUT
        }
        if (count > 0) { if (remaining > 0) remaining -= count; bytesTransferred(count) }
        return count
    }
    override fun getUri(): Uri? = uri
    override fun close() {
        try { handle?.close() } finally {
            handle = null; uri = null
            if (started) { started = false; transferEnded() }
        }
    }
    companion object {
        const val MAX_READ = 32 * 1024 * 1024
        fun trackUri(id: Long): Uri { require(id > 0); return Uri.parse("walkman://track/$id") }
        fun parseTrackId(uri: Uri): Long {
            require(uri.scheme == "walkman" && uri.host == "track" && uri.query == null && uri.fragment == null && uri.pathSegments.size == 1) { "Chỉ chấp nhận ID trong thư viện." }
            return uri.lastPathSegment?.toLongOrNull()?.takeIf { it > 0 } ?: throw IOException("ID bài hát không hợp lệ.")
        }
        fun factory(context: Context): DataSource.Factory {
            val app = context.applicationContext
            return DataSource.Factory {
                SafAudioDataSource { id -> runBlocking(Dispatchers.IO) {
                    val dao = LibraryDatabase.getInstance(app).libraryDao()
                    val track = dao.getTrackById(id) ?: throw IOException("Bài hát không còn trong chỉ mục.")
                    val root = dao.getRoot(track.rootId) ?: throw SecurityException("Thư mục chưa được cấp quyền.")
                    SafFileSource(app.contentResolver).open(
                        SourceRoot(root.id, root.uri, root.displayName),
                        SourceDocument(track.documentId, track.uri, track.parentId, track.name, track.mimeType, track.size, track.lastModified, false),
                    )
                } }
            }
        }
    }
}
