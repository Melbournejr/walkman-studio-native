// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.walkmanstudio.nativeplayer.core.SourceHandle
import java.io.EOFException
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackTest {
    @Test fun rejectsNetworkFileAndUnscopedContentUris() {
        for (text in listOf("https://example.com/1", "file:///sdcard/a.mp3", "content://provider/1", "walkman://track/../1", "walkman://track/1?x=2", "walkman://track/0", "walkman://track/-1", "walkman://track/9223372036854775808")) {
            var rejected = false
            try { SafAudioDataSource.parseTrackId(Uri.parse(text)) } catch (_: Exception) { rejected = true }
            assertTrue(text, rejected)
        }
    }
    @Test fun acceptsStableNumericIdOnly() {
        assertEquals(Long.MAX_VALUE, SafAudioDataSource.parseTrackId(SafAudioDataSource.trackUri(Long.MAX_VALUE)))
    }
    @Test fun exactPositionAndBoundedLengthArePreserved() {
        val handle = Bytes(ByteArray(100) { it.toByte() })
        val source = SafAudioDataSource { handle }
        assertEquals(7L, source.open(spec(20, 7)))
        val output = ByteArray(30)
        assertEquals(7, source.read(output, 3, 20))
        assertArrayEquals((20..26).map { it.toByte() }.toByteArray(), output.copyOfRange(3, 10))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(output, 0, 30))
        source.close(); assertTrue(handle.closed)
    }
    @Test fun unspecifiedLengthUsesRemainingFileBytes() {
        val source = SafAudioDataSource { Bytes(ByteArray(100)) }
        assertEquals(10L, source.open(spec(90)))
        source.close()
    }
    @Test fun unknownSizeIsStreamedWithoutMaterializingPayload() {
        val source = SafAudioDataSource { Bytes(byteArrayOf(4, 5, 6), null) }
        assertEquals(C.LENGTH_UNSET.toLong(), source.open(spec()))
        val output = ByteArray(8)
        assertEquals(3, source.read(output, 0, 8))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(output, 0, 8))
        source.close()
    }
    @Test fun truncatedKnownWindowFailsRatherThanClaimingSuccess() {
        val source = SafAudioDataSource { Bytes(byteArrayOf(1, 2), 7) }
        source.open(spec())
        val output = ByteArray(10)
        assertEquals(2, source.read(output, 0, 10))
        try { source.read(output, 0, 10); fail("Missing EOF failure") } catch (_: EOFException) { }
        source.close()
    }
    @Test fun openingNewWindowClosesPreviousDescriptor() {
        val handles = mutableListOf<Bytes>()
        val source = SafAudioDataSource { Bytes(ByteArray(10)).also { handles.add(it) } }
        source.open(spec()); source.open(spec(2))
        assertTrue(handles[0].closed); assertFalse(handles[1].closed)
        source.close(); assertTrue(handles[1].closed)
    }
    @Test fun failedSeekClosesOpenedDescriptor() {
        val handle = Bytes(ByteArray(2))
        val source = SafAudioDataSource { handle }
        try { source.open(spec(20)); fail("Seek should fail") } catch (_: IllegalArgumentException) { }
        assertTrue(handle.closed)
    }
    @Test fun memoryRoundTripsQueueAndExactPosition() {
        val memory = PlaybackMemory(listOf(1, 4, 88), 1, 129_345, true, Player.REPEAT_MODE_ALL)
        assertEquals(memory, PlaybackMemory.decode(memory.encode()))
    }
    @Test fun corruptMemoryCannotCreateAnUnboundedOrInvalidQueue() {
        assertNull(PlaybackMemory.decode(null)); assertNull(PlaybackMemory.decode("not json"))
        assertNull(PlaybackMemory.decode(PlaybackMemory(listOf(-1), 0, 0).encode()))
        assertNull(PlaybackMemory.decode(PlaybackMemory((1L..301L).toList(), 0, 0).encode()))
        val sanitized = PlaybackMemory.decode(PlaybackMemory(listOf(1), 9, -4).encode())!!
        assertEquals(0, sanitized.index); assertEquals(0L, sanitized.positionMs)
    }
    @Test fun taskSwipeKeepsRequestedPlaybackAndBuffering() {
        assertTrue(keepServiceAfterTaskRemoved(true, 2, Player.STATE_READY))
        assertTrue(keepServiceAfterTaskRemoved(true, 2, Player.STATE_BUFFERING))
        assertFalse(keepServiceAfterTaskRemoved(false, 2, Player.STATE_READY))
        assertFalse(keepServiceAfterTaskRemoved(true, 0, Player.STATE_READY))
        assertFalse(keepServiceAfterTaskRemoved(true, 2, Player.STATE_IDLE))
        assertFalse(keepServiceAfterTaskRemoved(true, 2, Player.STATE_ENDED))
    }
    @Test fun lifecycleLogIsBoundedValidJsonAndPersistedOffMain() {
        val app = RuntimeEnvironment.getApplication()
        val log = LifecycleLog.get(app)
        repeat(600) { log.record("test_event", "x".repeat(1100)) }
        log.flush().get(20, TimeUnit.SECONDS)
        val files = listOf(File(app.filesDir, "playback-events.jsonl"), File(app.filesDir, "playback-events.previous.jsonl"))
        assertTrue(files.all { it.exists() && it.length() in 1..LifecycleLog.MAX_BYTES })
        files.flatMap { it.readLines() }.forEach { line ->
            val entry = JSONObject(line)
            assertTrue(entry.has("wallTimeMs")); assertTrue(entry.has("elapsedMs")); assertEquals("test_event", entry.getString("event"))
        }
    }
    @Test fun logExportRejectsNetworkDestinations() {
        try { LifecycleLog.get(RuntimeEnvironment.getApplication()).export(Uri.parse("https://example.com/log")) {}; fail("Export must be local SAF") }
        catch (_: IllegalArgumentException) { }
    }
    private fun spec(position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()): DataSpec = DataSpec.Builder()
        .setUri(SafAudioDataSource.trackUri(1)).setPosition(position).setLength(length).build()
    private class Bytes(private val data: ByteArray, override val size: Long? = data.size.toLong()) : SourceHandle {
        var closed = false; private var position = 0
        override val seekable = true
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= data.size) return -1
            val count = minOf(length, data.size - position)
            data.copyInto(buffer, offset, position, position + count); position += count; return count
        }
        override fun seek(position: Long) { require(position in 0..data.size.toLong()); this.position = position.toInt() }
        override fun close() { closed = true }
    }
}
