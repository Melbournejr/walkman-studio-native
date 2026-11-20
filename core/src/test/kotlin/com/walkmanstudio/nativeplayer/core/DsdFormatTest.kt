// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.core

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class DsdFormatTest {
    private class Sparse(val bytes:ByteArray,override val length:Long?):DsdBytes {
        var readBytes=0L
        override fun read(position:Long,length:Int):ByteArray {readBytes+=length;assertTrue("Parser must not open audio payload",position+length<=bytes.size);return bytes.copyOfRange(position.toInt(),position.toInt()+length)}
    }
    private fun dsf(rate:Int=2822400,channels:Int=2,samples:Long=rate.toLong()):Pair<DsdHeader,Sparse> {
        val bytes=DsdTestFiles.dsfHeader(rate,channels,samples)
        val source=Sparse(bytes,ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(12))
        return DsdParser.parse(source) to source
    }
    @Test fun dsfRatesChannelsAndBitOrderComeFromHeader() {
        for(rate in listOf(2822400,5644800,11289600))for(channels in 1..2)for(lsb in listOf(true,false)) {
            val bytes=DsdTestFiles.dsfHeader(rate,channels,rate.toLong(),lsb=lsb)
            val h=DsdParser.parse(Sparse(bytes,null))
            assertEquals(rate,h.sampleRate);assertEquals(channels,h.channels);assertEquals(lsb,h.lsbFirst)
            assertEquals(88200L,h.totalFrames);assertEquals(1000000L,h.durationUs);assertEquals(92L,h.dataOffset)
        }
    }
    @Test fun dffReadsBigEndianPropertiesAndUncompressedPayload() {
        for(rate in listOf(2822400,5644800,11289600))for(channels in 1..2) {
            val bytes=DsdTestFiles.dffHeader(rate,channels,rate.toLong()/8*channels)
            val h=DsdParser.parse(Sparse(bytes,null))
            assertEquals(DsdKind.DFF,h.kind);assertEquals(rate,h.sampleRate);assertEquals(channels,h.channels)
            assertFalse(h.lsbFirst);assertEquals(1000000L,h.durationUs);assertEquals(bytes.size.toLong(),h.dataOffset)
        }
    }
    @Test fun fiveHundredMegabyteFileIsParsedWithoutReadingPayload() {
        val (h,source)=dsf(samples=250000000L*8)
        assertTrue(h.dataSize>=500000000);assertTrue(source.readBytes<512)
        assertEquals(h.totalFrames-1,h.frameAt(Long.MAX_VALUE/100))
    }
    @Test fun exactFrameWindowIncludesHistoryAndLookaheadAtEveryRate() {
        for(rate in listOf(2822400,5644800,11289600)) {
            val h=dsf(rate).first;val w=h.window(10000,2048)
            assertEquals(10000L*(h.ratio/8)-h.ratio*2,w.startByte)
            assertEquals(2047*(h.ratio/8)+h.ratio*4,w.bytesPerChannel)
            assertTrue(w.bytesPerChannel*h.channels<=32*1024*1024)
            for(f in listOf(0L,1,1000,h.totalFrames-1)) {
                val us=h.timeAt(f);assertTrue(kotlin.math.abs(h.frameAt(us)-f)<=1)
                assertTrue(h.physicalSeek(f)>=h.dataOffset && h.physicalSeek(f)<h.dataOffset+h.dataSize)
            }
        }
    }
    @Test fun dsfPhysicalRangesNeverMixChannelBlocks() {
        val h=dsf().first;val w=h.window(1000,2048)
        for(range in h.ranges(w))repeat(range.count) { i ->
            val destination=range.destination+i;val channel=destination/w.bytesPerChannel
            val logical=w.startByte+destination%w.bytesPerChannel
            val expected=h.dataOffset+(logical/h.blockSize)*h.blockSize*h.channels+channel*h.blockSize+logical%h.blockSize
            assertEquals(expected,range.position+i.toLong()*range.stride)
        }
    }
    @Test fun dffPhysicalRangesInterleaveAndFinalPartialByteStaysBounded() {
        val bytes=DsdTestFiles.dffHeader(2822400,2,100000)
        val h=DsdParser.parse(Sparse(bytes,null));val w=h.window(100,2048)
        for(range in h.ranges(w)) {assertEquals(2,range.stride);assertTrue(range.position+range.physicalBytes<=h.dataOffset+h.dataSize)}
        val short=dsf(samples=33).first;assertEquals(2L,short.totalFrames)
        val last=short.window(1,2048);assertEquals(1,last.frames);assertEquals(5L,last.readEnd)
        assertEquals(0,short.window(2,1).frames)
    }
    @Test fun dstDsd512AndSurroundAreExplicitlyUnsupported() {
        val dst=DsdTestFiles.dffHeader(2822400,2,100000,"DST ")
        for(bytes in listOf(dst,DsdTestFiles.dsfHeader(22579200,2,22579200),DsdTestFiles.dsfHeader(2822400,6,2822400))) {
            try{DsdParser.parse(Sparse(bytes,null));fail("Unsupported format accepted")}catch(_:UnsupportedDsdException){}
        }
    }
    @Test fun truncatedOverflowingAndInvalidChunksAreRejectedBeforeAllocation() {
        val original=DsdTestFiles.dsfHeader(2822400,2,2822400)
        val bad=original.copyOf().also{ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(32,Long.MAX_VALUE)}
        val high=original.copyOf().also{it[19]=0x80.toByte()}
        val dff=DsdTestFiles.dffHeader(2822400,2,100000).also{ByteBuffer.wrap(it).putLong(4,Long.MAX_VALUE)}
        for(bytes in listOf(bad,high,dff))try{DsdParser.parse(Sparse(bytes,null));fail("Invalid header accepted")}catch(_:IOException){}
        try{DsdParser.parse(Sparse(original,91));fail("Short source accepted")}catch(_:IOException){}
        try{dsf().first.window(Long.MAX_VALUE,8192);fail("Overflowing window accepted")}catch(_:ArithmeticException){}
    }
}
