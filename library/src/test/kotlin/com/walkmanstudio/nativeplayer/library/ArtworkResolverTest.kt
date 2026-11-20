// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.library

import android.graphics.Bitmap
import androidx.room.Room
import com.walkmanstudio.nativeplayer.core.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowBitmapFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ArtworkResolverTest {
    private lateinit var db:LibraryDatabase
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun setup()=runBlocking(Dispatchers.IO){
        ShadowBitmapFactory.setAllowInvalidImageData(false)
        db=Room.inMemoryDatabaseBuilder(app,LibraryDatabase::class.java).build()
        db.libraryDao().upsertRoot(LibraryRootEntity("r","content://test/tree/r","Music",1))
    }
    @After fun close(){db.close()}
    private fun png(color:Int):ByteArray {
        val bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);bitmap.eraseColor(color)
        return ByteArrayOutputStream().also{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it));bitmap.recycle()}.toByteArray()
    }
    private fun tagged(png:ByteArray):ByteArray {
        fun sync(n:Int)=byteArrayOf((n shr 21 and 127).toByte(),(n shr 14 and 127).toByte(),(n shr 7 and 127).toByte(),(n and 127).toByte())
        val payload=byteArrayOf(0)+"image/png".toByteArray()+byteArrayOf(0,3,0)+png
        val frame="APIC".toByteArray()+sync(payload.size)+byteArrayOf(0,0)+payload
        return "ID3".toByteArray()+byteArrayOf(4,0,0)+sync(frame.size)+frame
    }
    private suspend fun owner(key:String,size:Int){db.libraryDao().upsertTracks(listOf(TrackEntity(id=1,rootId="r",documentId="song",uri="content://test/tree/r/document/song",parentId="p",name="song.mp3",relativePath="album/song.mp3",mimeType="audio/mpeg",size=size.toLong(),lastModified=1,naturalSortKey="song",generation=1,title="Hello",artist="Adele",coverKey=key,metadataVersion=1)))}
    @Test fun evictedEmbeddedCoverIsRegeneratedOnceWithoutRewritingMetadata()=runBlocking(Dispatchers.IO){
        val art=png(0xff234567.toInt());val key=ArtworkCache.put(app,art)!!;val data=tagged(art);owner(key,data.size)
        ArtworkCache.invalidate(app,key);assertNull(ArtworkCache.file(app,key,128))
        val source=FakeSource(data)
        coroutineScope {(1..4).map{async{ArtworkResolver.resolve(app,key,128,database=db,source=source)}}.awaitAll().forEach{assertTrue(it!!.isFile)}}
        assertEquals(1,source.opens);assertEquals(source.opens,source.closes)
        assertNotNull(ArtworkCache.file(app,key,512));assertEquals("Adele",db.libraryDao().getTrackById(1)!!.artist)
        assertEquals(1,db.libraryDao().getTrackById(1)!!.metadataVersion)
    }
    @Test fun changedArtworkDoesNotAliasOldHashAndLostGrantDoesNotDeleteTrack()=runBlocking(Dispatchers.IO){
        val art=png(0xff456789.toInt());val key=ArtworkCache.put(app,art)!!;val data=tagged(png(0xffabcdef.toInt()));owner(key,data.size);ArtworkCache.invalidate(app,key)
        assertNull(ArtworkResolver.resolve(app,key,128,database=db,source=FakeSource(data)))
        assertNull(ArtworkCache.file(app,key,128))
        val denied=FakeSource(data).apply{this.denied=true}
        assertNull(ArtworkResolver.resolve(app,key,128,database=db,source=denied))
        assertTrue(db.libraryDao().getRoot("r")!!.permissionLost);assertEquals("Adele",db.libraryDao().getTrackById(1)!!.artist)
    }
    @Test fun folderCoverWinsCaseInsensitivelyAndUnknownLengthIsBounded()=runBlocking(Dispatchers.IO){
        val art=png(0xff678901.toInt());val key=ArtworkCache.put(app,art)!!;owner(key,32);ArtworkCache.invalidate(app,key)
        val source=FakeSource(ByteArray(32)).apply{cover=art;folder=png(0xff000000.toInt())}
        assertNotNull(ArtworkResolver.resolve(app,key,512,database=db,source=source))
        assertEquals(2,source.opens);assertEquals(2,source.closes)
    }
    private class FakeSource(private val song:ByteArray):FileSource {
        var opens=0;var closes=0;var denied=false;var cover:ByteArray?=null;var folder:ByteArray?=null
        override suspend fun root(root:SourceRoot)=SourceDocument("p",root.uri,null,"Music","vnd.android.document/directory",null,null,true)
        override fun children(root:SourceRoot,directoryId:String)=flow {
            folder?.let{emit(SourceDocument("folder","content://test/folder","p","Folder.JPG","image/jpeg",null,1,false))}
            cover?.let{emit(SourceDocument("cover","content://test/cover","p","COVER.jpg","image/jpeg",null,1,false))}
        }
        override suspend fun open(root:SourceRoot,document:SourceDocument):SourceHandle {
            if(denied)throw SecurityException("revoked")
            opens++;val bytes=when(document.id){"cover"->cover!!;"folder"->folder!!;else->song}
            return object:SourceHandle {
                override val size=bytes.size.toLong();override val seekable=true;var position=0
                override fun read(buffer:ByteArray,offset:Int,length:Int):Int {if(position==bytes.size)return -1;val n=minOf(length,bytes.size-position);bytes.copyInto(buffer,offset,position,position+n);position+=n;return n}
                override fun seek(position:Long){require(position in 0..size);this.position=position.toInt()}
                override fun close(){closes++}
            }
        }
    }
}
