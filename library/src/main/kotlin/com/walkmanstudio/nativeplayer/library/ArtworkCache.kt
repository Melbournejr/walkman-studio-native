// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow

/** Local, content-addressed image cache with pre-sized files; never fetches artwork. */
object ArtworkCache {
    val changes=MutableSharedFlow<String>(extraBufferCapacity=32,onBufferOverflow=BufferOverflow.DROP_OLDEST)
    private var lastTrim=0L
    private var pendingBytes=0L
    private const val MAX_BYTES = 96L * 1024 * 1024
    @Synchronized
    fun put(context: Context, bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size > MetadataReader.MAX_ART_BYTES) return null
        val key = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val dir = File(context.cacheDir, "artwork").also { it.mkdirs() }
        if (listOf(128, 512).all { File(dir, "$key-$it.jpg").isFile }) return key
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 100_000_000) return null
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        try {
            for (size in listOf(128, 512)) {
                val scale = minOf(1.0, size.toDouble() / maxOf(bitmap.width, bitmap.height))
                val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                try {
                    val target = File(dir, "$key-$size.jpg"); val temp = File(dir, "$key-$size.tmp")
                    temp.outputStream().use { require(scaled.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
                    if (!temp.renameTo(target)) { temp.copyTo(target, true); temp.delete() }
                } finally { if (scaled !== bitmap) scaled.recycle() }
            }
        } finally { bitmap.recycle() }
        pendingBytes+=listOf(128,512).sumOf{File(dir,"$key-$it.jpg").length()}
        val now=System.nanoTime()
        if(lastTrim==0L || now-lastTrim>10_000_000_000L || pendingBytes>=8L*1024*1024){trim(dir);lastTrim=now;pendingBytes=0}
        changes.tryEmit(key)
        return key
    }
    fun file(context: Context, key: String?, size: Int): File? {
        if (key == null || !key.matches(Regex("[0-9a-f]{64}"))) return null
        val requested = if (size <= 128) 128 else 512
        return File(context.cacheDir, "artwork/$key-$requested.jpg").takeIf { it.isFile }?.also { it.setLastModified(System.currentTimeMillis()) }
    }
    @Synchronized fun invalidate(context:Context,key:String){
        if(key.matches(Regex("[0-9a-f]{64}")))for(size in listOf(128,512))File(context.cacheDir,"artwork/$key-$size.jpg").delete()
    }
    private fun trim(dir: File) {
        val files = dir.listFiles()?.filter { it.extension == "jpg" }.orEmpty()
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            total -= file.length(); file.delete()
            if (total <= MAX_BYTES * 5 / 6) return
        }
    }
}
