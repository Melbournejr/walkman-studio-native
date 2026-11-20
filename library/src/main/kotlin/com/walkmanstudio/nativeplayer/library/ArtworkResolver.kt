// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.library

import android.content.Context
import com.walkmanstudio.nativeplayer.core.*
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Regenerate an evicted image on demand, from its granted original source only.
 * One request at a time coalesces simultaneous album rows without an unbounded key map.
 */
object ArtworkResolver {
    private val lock=Mutex()
    suspend fun resolve(context:Context,key:String,size:Int,force:Boolean=false,
        database:LibraryDatabase=LibraryDatabase.getInstance(context),source:FileSource=SafFileSource(context.contentResolver)):File? = withContext(Dispatchers.IO) {
        lock.withLock {
            if(!key.matches(Regex("[0-9a-f]{64}")))return@withLock null
            if(!force)ArtworkCache.file(context,key,size)?.let{return@withLock it}
            try {
                val dao=database.libraryDao();val track=dao.coverOwner(key)?:return@withLock null
                val root=dao.getRoot(track.rootId)?.takeUnless{it.permissionLost}?:return@withLock null
                val granted=SourceRoot(root.id,root.uri,root.displayName)
                val document=SourceDocument(track.documentId,track.uri,track.parentId,track.name,track.mimeType,track.size,track.lastModified,false)
                val tags=source.open(granted,document).use {handle->runInterruptible{MetadataReader().read(handle,track.name)}}
                var bytes=tags.artwork
                if(bytes==null && track.parentId!=null) {
                    var cover:SourceDocument?=null;var folder:SourceDocument?=null
                    source.children(granted,track.parentId).collect {
                        if(!it.isDirectory && it.name.equals("cover.jpg",true))cover=it
                        if(!it.isDirectory && it.name.equals("folder.jpg",true))folder=it
                    }
                    val candidate=cover?:folder
                    if(candidate!=null)bytes=source.open(granted,candidate).use {handle->
                        val out=ByteArrayOutputStream();val buffer=ByteArray(16384)
                        while(out.size()<=MetadataReader.MAX_ART_BYTES){
                            val n=handle.read(buffer,0,minOf(buffer.size,MetadataReader.MAX_ART_BYTES+1-out.size()))
                            if(n<0)break;if(n==0)throw java.io.IOException("Nguồn bìa không tiến thêm dữ liệu");out.write(buffer,0,n)
                        }
                        out.toByteArray().takeIf{it.size<=MetadataReader.MAX_ART_BYTES}
                    }
                }
                val art=bytes?:return@withLock null
                if(force)ArtworkCache.invalidate(context,key)
                val restored=ArtworkCache.put(context,art)
                if(restored==key)ArtworkCache.file(context,key,size) else null // File/tag changed: wait for a rescan, don't alias content hashes.
            }catch(cancel:CancellationException){throw cancel}
            catch(_:SecurityException){database.libraryDao().coverOwner(key)?.let{database.libraryDao().setPermissionLost(it.rootId,true)};null}
            catch(_:Exception){null}
        }
    }
}
