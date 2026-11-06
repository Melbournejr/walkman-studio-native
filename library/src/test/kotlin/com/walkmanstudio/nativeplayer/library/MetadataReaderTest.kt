// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.library

import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.walkmanstudio.nativeplayer.core.SourceHandle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(UnstableApi::class)
class MetadataReaderTest {
    @Before fun useStrictBitmapDecoderFixture() {
        // Robolectric's legacy decoder accepts arbitrary non-image bytes by
        // default. Match Android's rejection behavior without changing cache code.
        org.robolectric.shadows.ShadowBitmapFactory.setAllowInvalidImageData(false)
        java.io.File(org.robolectric.RuntimeEnvironment.getApplication().cacheDir, "artwork").deleteRecursively()
    }
    @Test fun readsRealId3v23UnicodeTagsInsteadOfFilename() {
        val data = id3("Hello tiếng Việt", "Adele", 3)
        val tags = MetadataReader().read(Bytes(data), "arbitrary filename.mp3")
        assertEquals("Hello tiếng Việt", tags.title); assertEquals("Adele", tags.artist)
        assertEquals("25", tags.album); assertEquals(2, tags.disc); assertEquals(10, tags.track)
    }
    @Test fun readsId3v24Utf8AndReplayGainTxxx() {
        val tags = MetadataReader().read(Bytes(id3("Hello", "Adele", 4)), "x.mp3")
        assertEquals("Hello", tags.title); assertEquals(-7.5, tags.trackGain!!, .000001)
    }
    @Test fun readsFlacVorbisCommentsAndStreamDuration() {
        val stream = ByteArray(34)
        stream[1] = 16; stream[3] = 16
        val packed = (44100L shl 44) or (1L shl 41) or (15L shl 36) or 44100
        ByteBuffer.wrap(stream, 10, 8).putLong(packed)
        val entries = listOf("TITLE=Real FLAC title", "ARTIST=Real artist", "ALBUM=Album", "TRACKNUMBER=9/12", "DISCNUMBER=2/2", "REPLAYGAIN_ALBUM_GAIN=-4.25 dB")
        val comments = le(1) + byteArrayOf(120) + le(entries.size) + entries.fold(byteArrayOf()) { result, text -> result + le(text.toByteArray().size) + text.toByteArray() }
        val data = "fLaC".toByteArray() + byteArrayOf(0, 0, 0, 34) + stream + byteArrayOf(0x84.toByte(), (comments.size shr 16).toByte(), (comments.size shr 8).toByte(), comments.size.toByte()) + comments + byteArrayOf(0xff.toByte(), 0xf8.toByte()) + ByteArray(32)
        val tags = MetadataReader().read(Bytes(data), "x.flac")
        assertEquals("Real FLAC title", tags.title); assertEquals("Real artist", tags.artist)
        assertEquals(9, tags.track); assertEquals(2, tags.disc); assertEquals(1000L, tags.durationMs)
        assertEquals(-4.25, tags.albumGain!!, .000001)
    }
    @Test fun readsApeFooterIncludingTrackAndGain() {
        val entries = listOf("Title" to "Ape title", "Artist" to "Ape artist", "Track" to "3/9", "Disc" to "2", "REPLAYGAIN_TRACK_GAIN" to "-6.50 dB")
        val items = entries.fold(byteArrayOf()) { output, (key, value) -> output + le(value.toByteArray().size) + le(0) + key.toByteArray() + byteArrayOf(0) + value.toByteArray() }
        val footer = "APETAGEX".toByteArray() + le(2000) + le(items.size + 32) + le(entries.size) + le(0) + ByteArray(8)
        val tags = MetadataReader().read(Bytes(ByteArray(16) + items + footer), "x.ape")
        assertEquals("Ape title", tags.title); assertEquals("Ape artist", tags.artist); assertEquals(3, tags.track)
        assertEquals(2, tags.disc); assertEquals(-6.5, tags.trackGain!!, .000001)
    }
    @Test fun readsDsfId3WithoutReadingFiveHundredMegabytePayload() {
        val size = 500L * 1024 * 1024
        val tag = id3("DSF title", "DSF artist", 4)
        val head = ByteArray(80)
        "DSD ".toByteArray().copyInto(head)
        ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).apply { putLong(4, 28); putLong(12, size); putLong(20, size - tag.size) }
        "fmt ".toByteArray().copyInto(head, 28)
        ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).apply { putLong(32, 52); putInt(56, 2_822_400); putLong(64, 2_822_400) }
        val source = Sparse(size, listOf(0L to head, size - tag.size to tag))
        val tags = MetadataReader().read(source, "x.dsf")
        assertEquals("DSF title", tags.title); assertEquals("DSF artist", tags.artist); assertEquals(1000L, tags.durationMs)
        assertTrue(source.bytesRead < 4096)
    }
    @Test fun readsDffId3AfterDsdDataAndPropertyChunks() {
        val fs = chunk("FS  ", be(2_822_400))
        val channels = chunk("CHNL", byteArrayOf(0, 2) + "SLFTSRGT".toByteArray())
        val prop = chunk("PROP", "SND ".toByteArray() + fs + channels)
        val tag = chunk("ID3 ", id3("DFF title", "DFF artist", 3))
        val body = "DSD ".toByteArray() + prop + chunk("DSD ", ByteArray(705_600)) + tag
        val data = "FRM8".toByteArray() + bigLong(body.size.toLong()) + body
        val tags = MetadataReader().read(Bytes(data), "x.dff")
        assertEquals("DFF title", tags.title); assertEquals(1000L, tags.durationMs)
    }
    @Test fun collectorAcceptsMp4IlstFramesAndFreeformReplayGain() {
        val collector = MetadataReader.Collector()
        collector.accept(Metadata(TextInformationFrame("TIT2", null, listOf("M4A title")), TextInformationFrame("TPE1", null, listOf("M4A artist")), InternalFrame("com.apple.iTunes", "replaygain_album_gain", "-3.25 dB")))
        assertEquals("M4A title", collector.result().title)
        assertEquals(-3.25, collector.result().albumGain!!, .000001)
    }
    @Test fun collectorHandlesOpusVorbisNumbersAndInvalidGainSafely() {
        val collector = MetadataReader.Collector()
        collector.accept(Metadata(VorbisComment("TRACKNUMBER", "02/12"), VorbisComment("DISCNUMBER", "1/2"), VorbisComment("REPLAYGAIN_TRACK_GAIN", "NaN"), VorbisComment("REPLAYGAIN_ALBUM_PEAK", "1.12")))
        assertEquals(2, collector.result().track); assertEquals(1, collector.result().disc)
        assertNull(collector.result().trackGain); assertEquals(1.12, collector.result().albumPeak!!, .000001)
    }
    @Test fun rejectsId3LengthBeforeAllocatingHugeTagBuffer() {
        val data = "ID3".toByteArray() + byteArrayOf(4, 0, 0, 127, 127, 127, 127) + ByteArray(6)
        try { MetadataReader().read(Bytes(data), "x.mp3"); fail("Oversized tag must be rejected") } catch (_: IllegalArgumentException) { }
    }
    @Test fun rejectsMp4OversizedMoovBeforeExtractorAllocation() {
        val header = be(16) + "ftypM4A ".toByteArray() + be(0) + be(100_000_000) + "moov".toByteArray()
        val source = Sparse(100_000_016, listOf(0L to header))
        try { MetadataReader().read(source, "x.m4a"); fail("Oversized moov must be rejected") } catch (_: java.io.IOException) { }
        assertTrue(source.bytesRead < 1024)
    }
    @Test fun changedSourceOrFolderInvalidatesMetadataResult() {
        val a = TrackEntity(1, "root", "doc", "content://x", "p", "a.mp3", "p/a.mp3", "audio/mpeg", 100, 20, "a", 1)
        assertTrue(MetadataWorker.sameSourceStamp(a, a.copy(generation = 2)))
        assertFalse(MetadataWorker.sameSourceStamp(a, a.copy(lastModified = 21)))
        assertFalse(MetadataWorker.sameSourceStamp(a, a.copy(parentId = "moved")))
        assertFalse(MetadataWorker.sameSourceStamp(a, a.copy(size = 101)))
    }
    @Test fun imageBoundsAndContentAddressedSizesAreEnforced() {
        val collector = MetadataReader.Collector()
        collector.cover(ByteArray(MetadataReader.MAX_ART_BYTES + 1)); assertNull(collector.result().artwork)
        assertNull(ArtworkCache.put(org.robolectric.RuntimeEnvironment.getApplication(), byteArrayOf(0, 1, 2)))
        assertNull(ArtworkCache.file(org.robolectric.RuntimeEnvironment.getApplication(), "../../private", 128))
    }
    @Test fun embeddedCoverIsReadFromId3Apic() {
        val image = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a8h8AAAAASUVORK5CYII=")
        val payload = byteArrayOf(0) + "image/png".toByteArray() + byteArrayOf(0, 3, 0) + image
        val frame = "APIC".toByteArray() + sync(payload.size) + byteArrayOf(0, 0) + payload
        val data = "ID3".toByteArray() + byteArrayOf(4, 0, 0) + sync(frame.size) + frame
        assertArrayEquals(image, MetadataReader().read(Bytes(data), "cover.mp3").artwork)
    }
    @Test fun cacheCreatesTwoBoundedImageSizesAndDeduplicatesContent() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val original = android.graphics.Bitmap.createBitmap(1024, 768, android.graphics.Bitmap.Config.ARGB_8888)
        original.eraseColor(0xff123456.toInt())
        val output = java.io.ByteArrayOutputStream()
        assertTrue(original.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
        original.recycle()
        val bytes = output.toByteArray()
        val key = ArtworkCache.put(app, bytes)!!
        assertEquals(key, ArtworkCache.put(app, bytes))
        val small = android.graphics.BitmapFactory.decodeFile(ArtworkCache.file(app, key, 128)!!.path)
        val large = android.graphics.BitmapFactory.decodeFile(ArtworkCache.file(app, key, 512)!!.path)
        assertEquals(128, small.width); assertEquals(96, small.height)
        assertEquals(512, large.width); assertEquals(384, large.height)
        small.recycle(); large.recycle()
    }
    private fun id3(title: String, artist: String, version: Int): ByteArray {
        fun text(value: String) = if (version == 3) byteArrayOf(1) + value.toByteArray(Charsets.UTF_16) else byteArrayOf(3) + value.toByteArray(Charsets.UTF_8)
        fun frame(id: String, bytes: ByteArray) = id.toByteArray() + (if (version == 4) sync(bytes.size) else be(bytes.size)) + byteArrayOf(0, 0) + bytes
        val gain = if (version == 4) byteArrayOf(3) + "REPLAYGAIN_TRACK_GAIN\u0000-7.50 dB".toByteArray() else byteArrayOf(0) + "REPLAYGAIN_TRACK_GAIN\u0000-7.50 dB".toByteArray()
        val frames = frame("TIT2", text(title)) + frame("TPE1", text(artist)) + frame("TALB", text("25")) + frame("TRCK", text("10/12")) + frame("TPOS", text("2/2")) + frame("TXXX", gain)
        return "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, 0) + sync(frames.size) + frames
    }
    private fun be(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
    private fun le(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun bigLong(value: Long) = ByteBuffer.allocate(8).putLong(value).array()
    private fun sync(value: Int) = byteArrayOf((value shr 21 and 127).toByte(), (value shr 14 and 127).toByte(), (value shr 7 and 127).toByte(), (value and 127).toByte())
    private fun chunk(id: String, body: ByteArray) = id.toByteArray() + bigLong(body.size.toLong()) + body + if (body.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
    private class Bytes(private val bytes: ByteArray) : SourceHandle {
        override val size = bytes.size.toLong(); override val seekable = true; private var position = 0
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(length, bytes.size - position); bytes.copyInto(buffer, offset, position, position + n); position += n; return n
        }
        override fun seek(position: Long) { require(position in 0..size); this.position = position.toInt() }
        override fun close() { }
    }
    private class Sparse(override val size: Long, val regions: List<Pair<Long, ByteArray>>) : SourceHandle {
        override val seekable = true; var position = 0L; var bytesRead = 0
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = minOf(length.toLong(), size - position).toInt(); if (n <= 0) return -1
            for (i in 0 until n) { val at = position + i; buffer[offset + i] = regions.firstOrNull { at >= it.first && at - it.first < it.second.size }?.let { it.second[(at - it.first).toInt()] } ?: 0 }
            position += n; bytesRead += n; return n
        }
        override fun seek(position: Long) { require(position in 0..size); this.position = position }
        override fun close() { }
    }
}
