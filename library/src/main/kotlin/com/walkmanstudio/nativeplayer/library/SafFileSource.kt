// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Walkman Studio Native contributors
package com.walkmanstudio.nativeplayer.library

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.walkmanstudio.nativeplayer.core.FileSource
import com.walkmanstudio.nativeplayer.core.SourceDocument
import com.walkmanstudio.nativeplayer.core.SourceHandle
import com.walkmanstudio.nativeplayer.core.SourceRoot
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.io.InputStream
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** SAF-only, read-only access. Every document URI is constructed from the retained tree. */
class SafFileSource(private val resolver: ContentResolver) : FileSource {
    override suspend fun root(root: SourceRoot): SourceDocument = withContext(Dispatchers.IO) {
        val tree = retainedTree(root)
        val id = DocumentsContract.getTreeDocumentId(tree)
        query(DocumentsContract.buildDocumentUriUsingTree(tree, id)).use { cursor ->
            requireCompleteSafCursor(cursor)
            if (!cursor.moveToFirst()) throw IOException("Thư mục không còn tồn tại hoặc chưa sẵn sàng.")
            val document = document(cursor, tree, null)
            requireCompleteSafCursor(cursor)
            document
        }
    }

    override fun children(root: SourceRoot, directoryId: String): Flow<SourceDocument> = flow {
        val tree = retainedTree(root)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directoryId)
        // Framework DocumentsProvider enforces the tree grant even if directoryId is untrusted.
        query(childrenUri).use { cursor ->
            requireCompleteSafCursor(cursor)
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                emit(document(cursor, tree, directoryId))
            }
            // A provider may return an incomplete cursor while loading. Never prune on this result.
            requireCompleteSafCursor(cursor)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun open(root: SourceRoot, document: SourceDocument): SourceHandle = withContext(Dispatchers.IO) {
        require(!document.isDirectory) { "Không thể đọc thư mục như tệp." }
        val tree = retainedTree(root)
        // Ignore document.uri, which could contain an arbitrary authority or another granted tree.
        val scoped = DocumentsContract.buildDocumentUriUsingTree(tree, document.id)
        SafSourceHandle(
            opener = {
                resolver.openFileDescriptor(scoped, "r")
                    ?: throw IOException("Không mở được tệp đã chọn.")
            },
            declaredSize = document.size,
        )
    }

    private fun retainedTree(root: SourceRoot): Uri {
        val tree = Uri.parse(root.uri)
        require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree)) { "Nguồn phải là thư mục SAF." }
        if (resolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission }) {
            throw SecurityException("Quyền đọc thư mục đã hết; hãy chọn lại thư mục.")
        }
        return tree
    }

    private suspend fun query(uri: Uri): Cursor = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        try {
            val cursor = resolver.query(uri, PROJECTION, null, null, null, signal)
                ?: throw IOException("Nhà cung cấp tệp không trả dữ liệu.")
            continuation.resume(cursor) { _, value, _ -> value.close() }
        } catch (failure: Throwable) {
            continuation.resumeWithException(failure)
        }
    }

    private fun document(cursor: Cursor, tree: Uri, parent: String?): SourceDocument {
        fun text(column: String): String? = cursor.getColumnIndex(column).takeIf { it >= 0 }
            ?.let { if (cursor.isNull(it)) null else cursor.getString(it) }
        fun number(column: String): Long? = cursor.getColumnIndex(column).takeIf { it >= 0 }
            ?.let { if (cursor.isNull(it)) null else cursor.getLong(it) }
        val id = text(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            ?.takeIf { it.isNotBlank() } ?: throw IOException("Thiếu document ID trong nguồn SAF.")
        val mime = text(DocumentsContract.Document.COLUMN_MIME_TYPE)
            ?: throw IOException("Thiếu loại tài liệu trong nguồn SAF.")
        return SourceDocument(
            id = id,
            uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
            parentId = parent,
            name = text(DocumentsContract.Document.COLUMN_DISPLAY_NAME) ?: id.substringAfterLast('/'),
            mimeType = mime,
            size = number(DocumentsContract.Document.COLUMN_SIZE)?.takeIf { it >= 0 },
            lastModified = number(DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.takeIf { it > 0 },
            isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
        )
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

/** A partial/error cursor is not evidence that indexed music disappeared. */
internal fun requireCompleteSafCursor(cursor: Cursor) {
    val extras = cursor.extras
    val error = extras.getString(DocumentsContract.EXTRA_ERROR)
    if (!error.isNullOrBlank()) throw IOException("Nguồn SAF chưa đọc được danh sách: $error")
    if (extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
        throw IOException("Nguồn SAF đang tải danh sách chưa đầy đủ. Hãy thử quét lại.")
    }
}

/** Android adapter owns the PFD through AutoCloseInputStream. */
class SafSourceHandle(opener: () -> ParcelFileDescriptor, declaredSize: Long? = null) : SourceHandle by
    ReopenableSourceHandle(
        opener = {
            val descriptor = opener()
            val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            try {
                OpenedSource(input, descriptor.statSize.takeIf { it >= 0 }) { target ->
                    input.channel.position(target)
                }
            } catch (failure: Throwable) {
                input.close()
                throw failure
            }
        },
        declaredSize = declaredSize,
    )

/** Internal transport seam: tests use an actual host pipe, not Robolectric's file-backed pipe shadow. */
internal class OpenedSource(
    val input: InputStream,
    val reportedSize: Long?,
    val reposition: (Long) -> Unit,
)

/** Shared production algorithm. A pipe is rewound by reopening, then discarded in bounded buffers. */
internal class ReopenableSourceHandle(
    private val opener: () -> OpenedSource,
    declaredSize: Long? = null,
) : SourceHandle {
    private var source = opener()
    private var position = 0L
    private var closed = false
    override val size: Long? = source.reportedSize?.takeIf { it >= 0 } ?: declaredSize?.takeIf { it >= 0 }
    override val seekable: Boolean = try {
        source.reposition(0L)
        true
    } catch (_: IOException) { false }

    @Synchronized
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "Source handle is closed" }
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) { "Invalid read window" }
        require(length <= MAX_READ_BYTES) { "Read window exceeds 32 MiB" }
        if (length == 0) return 0
        val count = source.input.read(buffer, offset, length)
        if (count > 0) position += count
        return count
    }

    @Synchronized
    override fun seek(position: Long) {
        check(!closed) { "Source handle is closed" }
        require(position >= 0 && (size == null || position <= size!!)) { "Seek outside source" }
        if (seekable) {
            source.reposition(position)
            this.position = position
            return
        }
        if (position < this.position) {
            source.input.close()
            source = opener()
            this.position = 0
        }
        val skipBuffer = ByteArray(16 * 1024)
        while (this.position < position) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Source seek interrupted")
            val wanted = minOf(skipBuffer.size.toLong(), position - this.position).toInt()
            val read = source.input.read(skipBuffer, 0, wanted)
            if (read < 0) throw EOFException("Nguồn kết thúc trước vị trí cần tua.")
            if (read == 0) throw IOException("Nguồn không tiến thêm dữ liệu khi tua.")
            this.position += read
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            source.input.close()
        }
    }

    private companion object { const val MAX_READ_BYTES = 32 * 1024 * 1024 }
}
