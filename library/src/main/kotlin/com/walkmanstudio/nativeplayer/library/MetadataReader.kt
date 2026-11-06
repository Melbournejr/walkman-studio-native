// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.library

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.metadata.flac.PictureFrame
import androidx.media3.extractor.metadata.id3.ApicFrame
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.walkmanstudio.nativeplayer.core.SourceHandle
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

data class MusicTags(
    val title: String? = null, val artist: String? = null, val album: String? = null,
    val albumArtist: String? = null, val disc: Int? = null, val track: Int? = null,
    val durationMs: Long? = null, val artwork: ByteArray? = null,
    val trackGain: Double? = null, val albumGain: Double? = null,
    val trackPeak: Double? = null, val albumPeak: Double? = null,
)

/** Bounded tag reads; standard container/tag parsing uses maintained Media3 extractors. */
@androidx.annotation.OptIn(UnstableApi::class)
class MetadataReader {
    fun read(source: SourceHandle, name: String): MusicTags {
        val tags = Collector()
        val header = at(source, 0, minOf(source.size ?: 16L, 16L).toInt())
        when {
            header.ascii(0, 4) == "DSD " -> readDsf(source, tags)
            header.ascii(0, 4) == "FRM8" -> readDff(source, tags)
            else -> {
                if (header.ascii(0, 3) == "ID3") {
                    val size = id3Size(header)
                    tags.accept(Id3Decoder().decode(at(source, 0, size), size))
                }
                readApe(source, tags)
                validateMp4Atoms(source, header)
                extract(source, name, tags)
            }
        }
        return tags.result()
    }

    private fun extract(source: SourceHandle, name: String, tags: Collector) {
        var consumed = 0L
        val start = System.nanoTime()
        val reader = DataReader { bytes, offset, length ->
            if (consumed + length > MAX_TAG_BYTES * 2L || System.nanoTime() - start > 3_000_000_000L) throw IOException("Giới hạn đọc metadata")
            source.read(bytes, offset, minOf(length, 32 * 1024 * 1024)).also { if (it > 0) consumed += it }
        }
        fun input(position: Long): DefaultExtractorInput {
            source.seek(position)
            return DefaultExtractorInput(reader, position, source.size ?: C.LENGTH_UNSET.toLong())
        }
        val extractors = DefaultExtractorsFactory().createExtractors(Uri.Builder().scheme("walkman").authority("metadata").appendPath(name).build(), emptyMap())
        var selected: Extractor? = null
        for (extractor in extractors) {
            try { if (extractor.sniff(input(0))) { selected = extractor; break } }
            catch (_: EOFException) { }
            catch (_: androidx.media3.common.ParserException) { }
        }
        val extractor = selected ?: return
        var gotFormat = false
        var gotMap = false
        val scratch = ByteArray(16 * 1024)
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
                override fun durationUs(durationUs: Long) { if (type == C.TRACK_TYPE_AUDIO && durationUs > 0) tags.duration = durationUs / 1000 }
                override fun format(format: Format) {
                    if (type == C.TRACK_TYPE_AUDIO) { gotFormat = true; tags.accept(format.metadata) }
                }
                override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                    val count = input.read(scratch, 0, minOf(length, scratch.size))
                    if (count < 0 && !allowEndOfInput) throw EOFException()
                    return count
                }
                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) { data.skipBytes(length) }
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) { }
            }
            override fun endTracks() { }
            override fun seekMap(seekMap: SeekMap) {
                gotMap = true
                if (seekMap.durationUs > 0) tags.duration = seekMap.durationUs / 1000
            }
        })
        try {
            var current = input(0)
            val seek = PositionHolder()
            repeat(20_000) {
                if (gotFormat && gotMap) return
                when (extractor.read(current, seek)) {
                    Extractor.RESULT_END_OF_INPUT -> return
                    Extractor.RESULT_SEEK -> current = input(seek.position)
                }
            }
        } finally { extractor.release() }
    }

    private fun readDsf(source: SourceHandle, tags: Collector) {
        val header = at(source, 0, 28)
        val id3 = header.long(20, true)
        val fmt = at(source, 28, 52)
        if (fmt.ascii(0, 4) == "fmt ") {
            val rate = fmt.uint(28, true)
            val samples = fmt.long(36, true)
            if (rate > 0 && samples >= 0) tags.duration = (samples.toDouble() * 1000 / rate).toLong()
        }
        if (id3 > 0) readId3(source, id3, tags)
    }
    private fun readDff(source: SourceHandle, tags: Collector) {
        val header = at(source, 0, 16)
        require(header.ascii(12, 4) == "DSD ") { "FRM8 không phải DSD" }
        val end = minOf(source.size ?: Long.MAX_VALUE, checkedEnd(12, header.long(4, false)))
        var rate = 0L; var channels = 0L; var dataSize = 0L
        chunks(source, 16, end) { id, body, length ->
            when (id) {
                "ID3 ", "ID3" -> readId3(source, body, tags)
                "DSD " -> dataSize = length
                "PROP" -> if (length >= 4 && at(source, body, 4).ascii(0, 4) == "SND ") {
                    chunks(source, body + 4, body + length) { child, offset, size ->
                        if (child == "FS  " && size >= 4) rate = at(source, offset, 4).uint(0, false)
                        if (child == "CHNL" && size >= 2) channels = at(source, offset, 2).let { (it[0].toInt() and 255) * 256L + (it[1].toInt() and 255) }
                    }
                }
            }
        }
        if (rate > 0 && channels > 0) tags.duration = (dataSize.toDouble() * 8000 / (rate * channels)).toLong()
    }
    private fun chunks(source: SourceHandle, start: Long, end: Long, visit: (String, Long, Long) -> Unit) {
        var offset = start
        var count = 0
        while (offset <= end - 12) {
            if (++count > 10_000) throw IOException("Quá nhiều khối metadata")
            val header = at(source, offset, 12); val size = header.long(4, false); val body = offset + 12
            val next = checkedEnd(body, size)
            if (next > end) throw EOFException("Khối metadata vượt nguồn")
            visit(header.ascii(0, 4), body, size)
            offset = checkedEnd(next, size and 1)
        }
    }
    private fun readId3(source: SourceHandle, offset: Long, tags: Collector) {
        val header = at(source, offset, 10)
        if (header.ascii(0, 3) != "ID3") return
        val size = id3Size(header)
        tags.accept(Id3Decoder().decode(at(source, offset, size), size))
    }
    private fun readApe(source: SourceHandle, tags: Collector) {
        var end = source.size ?: return
        if (end >= 128 && at(source, end - 128, 3).ascii(0, 3) == "TAG") end -= 128
        if (end < 32) return
        val footer = at(source, end - 32, 32)
        if (footer.ascii(0, 8) != "APETAGEX") return
        val length = footer.uint(12, true); val count = footer.uint(16, true)
        require(length in 32..MAX_TAG_BYTES.toLong() && length <= end && count <= 100_000) { "APE tag vượt giới hạn" }
        val data = at(source, end - length, length.toInt() - 32)
        var position = 0
        repeat(count.toInt()) {
            require(position <= data.size - 8)
            val valueSize = data.uint(position, true); val flags = data.uint(position + 4, true); position += 8
            val keyStart = position
            while (position < data.size && data[position] != 0.toByte() && position - keyStart <= 255) position++
            require(position < data.size && position - keyStart <= 255)
            val key = data.copyOfRange(keyStart, position).toString(Charsets.UTF_8); position++
            require(valueSize <= data.size - position)
            val bytes = data.copyOfRange(position, position + valueSize.toInt()); position += valueSize.toInt()
            if ((flags shr 1) and 3L == 0L) tags.text(key, bytes.toString(Charsets.UTF_8).substringBefore('\u0000'))
            else if (key.equals("Cover Art (Front)", true)) {
                val nul = bytes.indexOf(0)
                if (nul >= 0) tags.cover(bytes.copyOfRange(nul + 1, bytes.size))
            }
        }
    }
    private fun validateMp4Atoms(source: SourceHandle, header: ByteArray) {
        if (header.ascii(4, 4) != "ftyp") return
        val total = source.size ?: return
        var position = 0L; var count = 0
        while (position <= total - 8) {
            if (++count > 4096) throw IOException("Quá nhiều atom MP4")
            val atom = at(source, position, minOf(16L, total - position).toInt())
            var size = atom.uint(0, false)
            if (size == 1L) size = atom.long(8, false)
            if (size == 0L) size = total - position
            require(size >= 8 && size <= total - position)
            if (atom.ascii(4, 4) == "moov" && size > MAX_TAG_BYTES) throw IOException("Atom metadata quá lớn")
            position += size
        }
    }

    class Collector {
        private val builder = MediaMetadata.Builder()
        private val values = HashMap<String, String>()
        private var art: ByteArray? = null
        var duration: Long? = null
        fun accept(metadata: Metadata?) {
            if (metadata == null) return
            for (index in 0 until metadata.length()) {
                val entry = metadata[index]
                entry.populateMediaMetadata(builder)
                when (entry) {
                    is TextInformationFrame -> if (entry.id == "TXXX" || entry.id == "TXX") text(entry.description.orEmpty(), entry.values.first())
                    is InternalFrame -> text(entry.description, entry.text)
                    is VorbisComment -> text(entry.key, entry.value)
                    is ApicFrame -> cover(entry.pictureData)
                    is PictureFrame -> cover(entry.pictureData)
                }
            }
        }
        fun text(key: String, value: String) {
            val name = key.uppercase(Locale.ROOT).replace(" ", "")
            values[name] = value.trim().take(16_384)
            when (name) {
                "TITLE" -> builder.setTitle(value)
                "ARTIST" -> builder.setArtist(value)
                "ALBUM" -> builder.setAlbumTitle(value)
                "ALBUMARTIST", "ALBUM_ARTIST" -> builder.setAlbumArtist(value)
                "TRACK", "TRACKNUMBER" -> number(value)?.let { builder.setTrackNumber(it) }
                "DISC", "DISCNUMBER" -> number(value)?.let { builder.setDiscNumber(it) }
            }
        }
        fun cover(bytes: ByteArray) { if (art == null && bytes.size in 1..MAX_ART_BYTES) art = bytes }
        fun result(): MusicTags {
            val m = builder.build()
            fun gain(key: String) = values[key]?.substringBefore(' ')?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -60.0..60.0 }
            fun peak(key: String) = values[key]?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= 64 }
            fun clean(value: CharSequence?) = value?.toString()?.trim()?.take(16_384)?.takeIf { it.isNotEmpty() }
            return MusicTags(clean(m.title), clean(m.artist), clean(m.albumTitle), clean(m.albumArtist), m.discNumber?.takeIf { it > 0 }, m.trackNumber?.takeIf { it > 0 }, duration, art,
                gain("REPLAYGAIN_TRACK_GAIN"), gain("REPLAYGAIN_ALBUM_GAIN"), peak("REPLAYGAIN_TRACK_PEAK"), peak("REPLAYGAIN_ALBUM_PEAK"))
        }
        private fun number(value: String) = value.substringBefore('/').trim().toIntOrNull()?.takeIf { it > 0 }
    }
    companion object {
        const val VERSION = 1
        const val MAX_TAG_BYTES = 16 * 1024 * 1024
        const val MAX_ART_BYTES = 8 * 1024 * 1024
        private fun checkedEnd(start: Long, size: Long): Long { require(start >= 0 && size >= 0 && size <= Long.MAX_VALUE - start); return start + size }
        private fun at(source: SourceHandle, offset: Long, length: Int): ByteArray {
            require(offset >= 0 && length in 0..MAX_TAG_BYTES)
            source.size?.let { require(offset <= it && length.toLong() <= it - offset) }
            source.seek(offset)
            val bytes = ByteArray(length); var count = 0
            while (count < length) { val read = source.read(bytes, count, length - count); if (read <= 0) throw EOFException(); count += read }
            return bytes
        }
        private fun id3Size(header: ByteArray): Int {
            require(header.size >= 10)
            var size = 0
            for (i in 6..9) { require(header[i].toInt() and 128 == 0); size = (size shl 7) or (header[i].toInt() and 127) }
            require(size <= MAX_TAG_BYTES - 10) { "ID3 vượt giới hạn metadata" }
            return size + 10
        }
        private fun ByteArray.ascii(offset: Int, count: Int) = if (offset + count <= size) String(this, offset, count, Charsets.US_ASCII) else ""
        private fun ByteArray.uint(offset: Int, little: Boolean): Long = ByteBuffer.wrap(this, offset, 4).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
        private fun ByteArray.long(offset: Int, little: Boolean): Long = ByteBuffer.wrap(this, offset, 8).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).long.also { require(it >= 0) { "Số nguyên metadata vượt miền Long" } }
    }
}
