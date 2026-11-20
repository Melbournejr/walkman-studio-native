// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import com.walkmanstudio.nativeplayer.library.ArtworkResolver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.walkmanstudio.nativeplayer.library.ArtworkCache
import com.walkmanstudio.nativeplayer.library.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val artworkMemory = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}

@Composable
fun TrackCover(track: TrackEntity?, modifier: Modifier = Modifier.size(48.dp), large: Boolean = false) {
    AlbumCover(track?.coverKey, track?.let { "${it.albumArtist ?: it.artist.orEmpty()}|${it.album ?: it.relativePath.substringBeforeLast('/', "")}" }.orEmpty(), modifier, large)
}

@Composable
fun AlbumCover(key: String?, identity: String, modifier: Modifier, large: Boolean = false) {
    val context = LocalContext.current.applicationContext
    val tier = if (large) 512 else 128
    val bitmap by produceState<Bitmap?>(null, key, tier) {
        value=null
        value = if (key == null) null else withContext(Dispatchers.IO) {
            val memoryKey = "$key-$tier"
            artworkMemory.get(memoryKey) ?: run {
                val cached=ArtworkCache.file(context,key,tier)
                val decoded=cached?.let{BitmapFactory.decodeFile(it.path)}
                val bitmap=decoded ?: ArtworkResolver.resolve(context,key,tier,force=cached!=null)?.let{BitmapFactory.decodeFile(it.path)}
                bitmap?.also{artworkMemory.put(memoryKey,it)}
            }
        }
    }
    val shape=RoundedCornerShape(if(large)12.dp else 6.dp)
    val image = bitmap
    if (image != null) Image(image.asImageBitmap(), contentDescription = null, modifier = modifier.clip(shape), contentScale = ContentScale.Crop)
    else {
        val background = MaterialTheme.colorScheme.primaryContainer
        val ink = MaterialTheme.colorScheme.onPrimaryContainer
        val hash = remember(identity){java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())}
        Box(modifier.clip(shape).background(background), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val cell = size.minDimension / 7
                val left = (size.width - cell * 5) / 2; val top = (size.height - cell * 5) / 2
                for (x in 0..2) for (y in 0..4) if (hash[y * 3 + x].toInt() and 1 == 1) {
                    drawRect(ink.copy(alpha=.18f),Offset(left+x*cell,top+y*cell),Size(cell*.9f,cell*.9f))
                    if(x!=2)drawRect(ink.copy(alpha=.18f),Offset(left+(4-x)*cell,top+y*cell),Size(cell*.9f,cell*.9f))
                }
            }
            Text(identity.substringAfter('|').trim().take(2).uppercase(java.util.Locale.ROOT).ifBlank { "♪" }, color = ink)
        }
    }
}
