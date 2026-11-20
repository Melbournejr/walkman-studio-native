// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Walkman Studio Native contributors
package com.walkmanstudio.nativeplayer.library

import android.os.ParcelFileDescriptor
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SafSourceHandleTest {
    @Test fun seekableFileReadsExactWindowAndCloses() {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "pfd-seek-${System.nanoTime()}")
        file.writeBytes(ByteArray(1024) { (it % 251).toByte() })
        val handle = SafSourceHandle({ ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) })
        assertTrue(handle.seekable)
        assertEquals(1024L, handle.size)
        handle.seek(500)
        val bytes = ByteArray(20)
        assertEquals(10, handle.read(bytes, 5, 10))
        assertArrayEquals(ByteArray(10) { ((500 + it) % 251).toByte() }, bytes.copyOfRange(5, 15))
        handle.seek(0)
        assertEquals(0, handle.read(ByteArray(0), 0, 0))
        handle.close()
        expect<IllegalStateException> { handle.read(bytes, 0, 1) }
        file.delete()
    }

    @Test fun rejectsNegativeOutOfBoundsAndOversizedReads() {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "pfd-bounds-${System.nanoTime()}")
        file.writeText("12345")
        SafSourceHandle({ ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }).use { handle ->
            expect<IllegalArgumentException> { handle.seek(-1) }
            expect<IllegalArgumentException> { handle.seek(6) }
            expect<IllegalArgumentException> { handle.read(ByteArray(8), 7, 2) }
            expect<IllegalArgumentException> { handle.read(ByteArray(8), 0, Int.MAX_VALUE) }
            val large = ByteArray(32 * 1024 * 1024 + 1)
            expect<IllegalArgumentException> { handle.read(large, 0, large.size) }
        }
        file.delete()
    }

    @Test fun nonSeekablePipeReopensWhenSeekingBackwards() {
        val opens = AtomicInteger()
        val bytes = ByteArray(400) { (it % 127).toByte() }
        val opener = {
            opens.incrementAndGet()
            hostPipe(bytes)
        }
        ReopenableSourceHandle(opener).use { handle ->
            assertFalse(handle.seekable)
            assertNull(handle.size)
            handle.seek(200)
            val output = ByteArray(5)
            assertEquals(5, handle.read(output, 0, 5))
            assertArrayEquals(bytes.copyOfRange(200, 205), output)
            handle.seek(2)
            assertEquals(2, opens.get())
            assertEquals(5, handle.read(output, 0, 5))
            assertArrayEquals(bytes.copyOfRange(2, 7), output)
        }
    }

    @Test fun nonSeekableShortSourceThrowsRatherThanPretendingSeekSucceeded() {
        val opener = {
            hostPipe(byteArrayOf(1, 2, 3))
        }
        ReopenableSourceHandle(opener).use { handle -> expect<EOFException> { handle.seek(30) } }
    }

    /**
     * Robolectric4.16.1 ShadowParcelFileDescriptor.createPipe uses a RandomAccessFile;
     * it is seekable and reports file length. Use a real JVM pipe for the shared
     * production skip/reopen algorithm. Actual Android PFD pipe integration remains
     * a device/instrumentation test, not a claim made by this host test.
     */
    private fun hostPipe(bytes: ByteArray): OpenedSource {
        val pipe = Pipe.open()
        pipe.sink().use { sink ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) sink.write(buffer)
        }
        val channel: ReadableByteChannel = pipe.source()
        return OpenedSource(Channels.newInputStream(channel), null) { position ->
            if (channel is SeekableByteChannel) channel.position(position)
            else throw IOException("Pipe does not implement SeekableByteChannel")
        }
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit) {
        try { block(); fail("Expected ${T::class.java.simpleName}") }
        catch (error: Throwable) { if (error !is T) throw error }
    }
}
