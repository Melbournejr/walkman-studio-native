// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Walkman Studio Native contributors
package com.walkmanstudio.nativeplayer.core

import java.io.Closeable
import kotlinx.coroutines.flow.Flow

/** Stable source root: identifiers are provider IDs, never inferred display paths. */
data class SourceRoot(val id: String, val uri: String, val displayName: String)

data class SourceDocument(
    val id: String,
    val uri: String,
    val parentId: String?,
    val name: String,
    val mimeType: String,
    val size: Long?,
    val lastModified: Long?,
    val isDirectory: Boolean,
)

/**
 * Read-only, bounded-memory source. Implementations own/close their descriptors.
 * A non-seekable source may implement seek by reopen + buffered discard; callers
 * must not infer low seek latency from this interface.
 */
interface SourceHandle : Closeable {
    val size: Long?
    val seekable: Boolean
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun seek(position: Long)
}

interface FileSource {
    suspend fun root(root: SourceRoot): SourceDocument

    /** Stream one directory. Release its cursor on completion/error/cancellation. */
    fun children(root: SourceRoot, directoryId: String): Flow<SourceDocument>

    suspend fun open(root: SourceRoot, document: SourceDocument): SourceHandle
}
