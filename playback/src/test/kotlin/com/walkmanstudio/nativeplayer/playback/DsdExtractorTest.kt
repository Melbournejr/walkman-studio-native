// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback

import androidx.media3.common.*
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.*
import com.walkmanstudio.nativeplayer.core.*
import com.walkmanstudio.nativeplayer.playback.dsd.*
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@androidx.annotation.OptIn(UnstableApi::class)
class DsdExtractorTest {
    @Test fun dsfAndDffBothBitOrdersProduceIdenticalPcmAtAllRatesAndChannelCounts() {
        for(rate in listOf(2822400,5644800,11289600))for(channels in 1..2) {
            val frames=5000;val planes=Array(channels){c->ByteArray(frames*(rate/88200/8)){i->if(c==0)(i*31+7).toByte() else 0xEE.toByte()}}
            val expected=Harness(DsdTestFiles.dsf(rate,planes)).use {it.run()}
            val msb=planes.map {p->p.map(DsdTestFiles::reverseBits).toByteArray()}.toTypedArray()
            assertArrayEquals(expected,Harness(DsdTestFiles.dsf(rate,msb,block=17,lsb=false)).use{it.run()})
            Harness(DsdTestFiles.dff(rate,msb)).use { h ->
                assertArrayEquals(expected,h.run());assertEquals(C.ENCODING_PCM_FLOAT,h.format!!.pcmEncoding)
                assertEquals(88200,h.format!!.sampleRate);assertEquals(channels,h.format!!.channelCount)
                assertEquals(frames*channels*4,expected.size);assertTrue(h.maxRequested<=32*1024*1024)
            }
        }
    }
    @Test fun media3SeekMapAndExtractorSeekMatchLinearPcmIncludingFinalFrames() {
        val planes=Array(2){c->ByteArray(10000*16){i->(i*37+c*91).toByte()}}
        Harness(DsdTestFiles.dsf(11289600,planes)).use {h->
            val linear=h.run();val header=h.format!!.customData as DsdHeader
            for(first in listOf(0L,1,4095,4096,9997)) {
                val time=header.timeAt(first)+1;val actual=header.frameAt(time)
                val seek=h.run(time)
                assertArrayEquals(linear.copyOfRange((actual*8).toInt(),linear.size),seek)
                assertEquals(header.timeAt(actual),h.timestamps.first())
            }
        }
    }
    @Test fun sparseFiveHundredMegabyteFileSeeksWithBoundedReadsAndNoFullPayloadLoad() {
        val bytes=DsdTestFiles.dsfHeader(2822400,2,250000000L*8)
        val length=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(12)
        val meta=DsdParser.parse(object:DsdBytes{override val length=length;override fun read(position:Long,length:Int)=bytes.copyOfRange(position.toInt(),position.toInt()+length)})
        Harness(bytes,length).use {h->
            h.extractor.seek(0,meta.durationUs-10000)
            val result=h.run()
            assertTrue(result.size in 1..(2048*8));assertTrue(h.readBytes<2*1024*1024);assertTrue(h.maxRequested<=65536)
            assertTrue(length>=500000000)
        }
    }
    @Test fun dsfPaddingBitsOutsideSampleCountCannotChangeDecodedAudio() {
        for(lsb in listOf(true,false)) {
            val one=Array(2){byteArrayOf(0x99.toByte(),0x99.toByte(),0x99.toByte(),0x99.toByte(),if(lsb)1 else 0x80.toByte())}
            val two=one.map {it.copyOf().apply{this[4]=0xFF.toByte()}}.toTypedArray()
            val a=Harness(DsdTestFiles.dsf(2822400,one,samples=33,lsb=lsb)).use{it.run()}
            val b=Harness(DsdTestFiles.dsf(2822400,two,samples=33,lsb=lsb)).use{it.run()}
            assertArrayEquals(a,b);assertEquals(2*2*4,a.size)
        }
    }
    @Test fun truncatedSourceFailsReadAndSniffDoesNotConsumeValidInput() {
        val bytes=DsdTestFiles.dsf(2822400,arrayOf(ByteArray(1000){0x99.toByte()}))
        Harness(bytes).use {h->val input=h.input(0);assertTrue(h.extractor.sniff(input));assertEquals(0L,input.position)}
        Harness(bytes.copyOf(bytes.size-10)).use {h->try{h.run();fail("Truncated data accepted")}catch(_:IOException){}}
    }
    @Test fun dstIsReportedAsUnsupportedFeatureThroughMedia3() {
        val bytes=DsdTestFiles.dffHeader(2822400,2,1024,"DST ")+ByteArray(1024)
        Harness(bytes).use {h->
            try {h.run();fail("DST accepted")}catch(error:ParserException){assertFalse(error.contentIsMalformed);assertTrue(error.message.orEmpty().contains("DST"))}
        }
    }
    @Test fun nativeJniRejectsUndersizedBuffersAndClosedHandle() {
        val fir=NativeDsdFir(32,true)
        try { fir.decode(ByteBuffer.allocateDirect(2),128,2,1,ByteBuffer.allocateDirect(8));fail("Small input accepted") }catch(_:IllegalArgumentException){}
        fir.close();fir.close()
        try {fir.decode(ByteBuffer.allocateDirect(256),128,2,1,ByteBuffer.allocateDirect(8));fail("Closed decoder used")}catch(_:IllegalStateException){}
    }
    private class Harness(private val bytes:ByteArray,private val total:Long=bytes.size.toLong()):AutoCloseable {
        val extractor=DsdExtractor()
        var format:Format?=null;private var map:SeekMap?=null
        private val output=ByteArrayOutputStream();val timestamps=mutableListOf<Long>()
        var readBytes=0L;var maxRequested=0
        init {extractor.init(object:ExtractorOutput {
            override fun track(id:Int,type:Int)=object:TrackOutput {
                override fun format(format:Format){this@Harness.format=format}
                override fun sampleData(input:DataReader,length:Int,allowEndOfInput:Boolean,sampleDataPart:Int):Int=throw AssertionError("Native extractor emits decoded byte arrays")
                override fun sampleData(data:ParsableByteArray,length:Int,sampleDataPart:Int){val out=ByteArray(length);data.readBytes(out,0,length);output.write(out)}
                override fun sampleMetadata(timeUs:Long,flags:Int,size:Int,offset:Int,cryptoData:TrackOutput.CryptoData?){timestamps.add(timeUs)}
            }
            override fun endTracks(){}
            override fun seekMap(seekMap:SeekMap){map=seekMap}
        })}
        fun input(position:Long):DefaultExtractorInput {
            var at=position
            return DefaultExtractorInput(DataReader {buffer,offset,length->
                maxRequested=maxOf(maxRequested,length)
                if(at>=total)-1 else {
                    val n=minOf(length.toLong(),97,total-at).toInt()
                    repeat(n){i->buffer[offset+i]=if(at+i<bytes.size)bytes[(at+i).toInt()] else 0x99.toByte()}
                    at+=n;readBytes+=n;n
                }
            },position,total)
        }
        fun run(timeUs:Long?=null):ByteArray {
            output.reset();timestamps.clear()
            var at=0L
            if(timeUs!=null) {at=map!!.getSeekPoints(timeUs).first.position;extractor.seek(at,timeUs)}
            var input=input(at);val position=PositionHolder()
            repeat(50000) {
                when(extractor.read(input,position)) {
                    Extractor.RESULT_END_OF_INPUT->return output.toByteArray()
                    Extractor.RESULT_SEEK->input=input(position.position)
                }
            }
            throw AssertionError("Extractor did not finish bounded synthetic input")
        }
        override fun close(){extractor.release()}
    }
}
