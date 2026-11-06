// SPDX-License-Identifier: GPL-3.0-or-later
// Window layout follows Walkman Studio dsd-format.js (GPL-3.0-or-later), see NOTICE.
package com.walkmanstudio.nativeplayer.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UnsupportedDsdException(message:String):IOException(message)
interface DsdBytes { val length:Long?;fun read(position:Long,length:Int):ByteArray }
enum class DsdKind { DSF,DFF }
data class DsdHeader(val kind:DsdKind,val channels:Int,val sampleRate:Int,val sampleCount:Long,val lsbFirst:Boolean,
    val blockSize:Int,val dataOffset:Long,val dataSize:Long) {
    init {
        require(channels in 1..2 && sampleRate in listOf(2822400,5644800,11289600) && sampleCount>0)
        require(dataOffset>=0 && dataSize>0 && (kind!=DsdKind.DSF || blockSize in 1..1048576))
    }
    val ratio:Int get()=sampleRate/OUTPUT_RATE
    val totalFrames:Long get()=ceilDiv(sampleCount,ratio.toLong())
    val channelBytes:Long get()=ceilDiv(sampleCount,8)
    val durationUs:Long get()=scaleTime(sampleCount,sampleRate.toLong(),1000000)
    fun frameAt(timeUs:Long):Long = scaleTime(timeUs.coerceAtLeast(0),1000000,OUTPUT_RATE.toLong()).coerceAtMost(totalFrames-1)
    fun timeAt(frame:Long):Long = scaleTime(frame,OUTPUT_RATE.toLong(),1000000)
    fun window(firstFrame:Long,requested:Int):DsdWindow {
        require(firstFrame>=0 && requested in 1..8192)
        val count=minOf(requested.toLong(),(totalFrames-firstFrame).coerceAtLeast(0)).toInt()
        val start=Math.subtractExact(Math.multiplyExact(firstFrame,(ratio/8).toLong()),(ratio*2).toLong())
        val bytes=if(count==0)0 else Math.addExact((count-1)*(ratio/8),ratio*4)
        val readStart=start.coerceAtLeast(0);val end=minOf(channelBytes,Math.addExact(start,bytes.toLong()))
        return DsdWindow(firstFrame,count,start,bytes,readStart,end.coerceAtLeast(readStart))
    }
    fun ranges(window:DsdWindow):List<DsdRange> {
        if(window.readEnd<=window.readStart)return emptyList()
        val ranges=mutableListOf<DsdRange>()
        for(channel in 0 until channels) {
            var at=window.readStart
            while(at<window.readEnd) {
                val n=if(kind==DsdKind.DSF)minOf(window.readEnd-at,blockSize-at%blockSize).toInt() else (window.readEnd-at).toInt()
                val physical=if(kind==DsdKind.DSF) Math.addExact(dataOffset,Math.addExact(Math.multiplyExact(at/blockSize,blockSize.toLong()*channels),channel.toLong()*blockSize+at%blockSize))
                    else Math.addExact(dataOffset,Math.addExact(Math.multiplyExact(at,channels.toLong()),channel.toLong()))
                val stride=if(kind==DsdKind.DFF)channels else 1
                ranges.add(DsdRange(physical,n,(channel*window.bytesPerChannel+(at-window.startByte)).toInt(),stride))
                at+=n
            }
        }
        return ranges.sortedBy {it.position}
    }
    fun physicalSeek(frame:Long,cacheChunk:Int=65536):Long {
        val byte=window(frame.coerceIn(0,totalFrames-1),1).readStart
        val relative=if(kind==DsdKind.DSF) Math.multiplyExact(byte/blockSize,blockSize.toLong()*channels) else Math.multiplyExact(byte,channels.toLong())
        return Math.addExact(dataOffset,relative/cacheChunk*cacheChunk)
    }
    companion object {const val OUTPUT_RATE=88200}
}
data class DsdWindow(val firstFrame:Long,val frames:Int,val startByte:Long,val bytesPerChannel:Int,val readStart:Long,val readEnd:Long)
data class DsdRange(val position:Long,val count:Int,val destination:Int,val stride:Int) {val physicalBytes:Int get()=Math.addExact(Math.multiplyExact(count-1,stride),1)}
private fun ceilDiv(value:Long,divisor:Long):Long=value/divisor+if(value%divisor==0L)0 else 1
private fun scaleTime(value:Long,denominator:Long,numerator:Long):Long = Math.addExact(Math.multiplyExact(value/denominator,numerator),(value%denominator)*numerator/denominator)

object DsdParser {
    fun parse(source:DsdBytes):DsdHeader {
        try {
            val first=source.read(0,16)
            return when(text(first,0)) {"DSD "->dsf(source,first);"FRM8"->dff(source,first);else->throw IOException("Không nhận dạng được header DSF/DFF")}
        } catch(overflow:ArithmeticException) {throw IOException("Kích thước header DSD bị tràn",overflow)}
    }
    private fun dsf(source:DsdBytes,initial:ByteArray):DsdHeader {
        if(u64(initial,4,true)!=28L)throw IOException("Header DSF không hợp lệ")
        val full=source.read(0,28);val declared=u64(full,12,true)
        bound(0,28,declared);source.length?.let{bound(0,declared,it)}
        var offset=28L;var fmt:ByteArray?=null;var dataOffset=0L;var dataSize=0L
        repeat(10000) {
            bound(offset,12,declared);val chunk=source.read(offset,12);val size=u64(chunk,4,true)
            if(size<12)throw IOException("Kích thước chunk DSF không hợp lệ")
            bound(offset,size,declared)
            when(text(chunk,0)) {
                "fmt "->{if(fmt!=null || size<52)throw IOException("fmt DSF không hợp lệ");fmt=source.read(offset,52)}
                "data"->{if(dataOffset!=0L)throw IOException("Nhiều vùng DSD chưa hỗ trợ");dataOffset=offset+12;dataSize=size-12}
            }
            val format=fmt
            if(format!=null && dataOffset>0) {
                if(u32(format,12,true)!=1L || u32(format,16,true)!=0L)throw UnsupportedDsdException("Chưa hỗ trợ phiên bản/kiểu DSF này")
                val channels=u32(format,24,true);val rate=u32(format,28,true);supported(channels,rate)
                val bits=u32(format,32,true);if(bits!=1L && bits!=8L)throw UnsupportedDsdException("Thứ tự bit DSF chưa hỗ trợ")
                val block=u32(format,44,true);if(block !in 1..1048576)throw UnsupportedDsdException("Block DSF vượt giới hạn window")
                val samples=u64(format,36,true);if(samples<1)throw IOException("DSF không có mẫu")
                val minimum=Math.multiplyExact(Math.multiplyExact(ceilDiv(ceilDiv(samples,8),block),block),channels)
                if(minimum>dataSize)throw IOException("Dữ liệu DSF ngắn hơn số mẫu trong header")
                return DsdHeader(DsdKind.DSF,channels.toInt(),rate.toInt(),samples,bits==1L,block.toInt(),dataOffset,dataSize)
            }
            offset=Math.addExact(offset,size)
        }
        throw IOException("Quá nhiều chunk hoặc thiếu fmt/data DSF")
    }
    private fun dff(source:DsdBytes,initial:ByteArray):DsdHeader {
        if(text(initial,12)!="DSD ")throw UnsupportedDsdException("FRM8 không phải DSDIFF")
        val end=Math.addExact(u64(initial,4,false),12);bound(0,16,end);source.length?.let{bound(0,end,it)}
        var offset=16L;var channels=0L;var rate=0L;var compression:String?=null
        repeat(10000) {
            bound(offset,12,end);val chunk=source.read(offset,12);val size=u64(chunk,4,false);val body=offset+12;bound(body,size,end)
            when(text(chunk,0)) {
                "PROP"->{
                    if(size<4)throw IOException("PROP DFF thiếu type")
                    if(text(source.read(body,4),0)=="SND ") {
                        var sub=body+4;val limit=body+size;var chunks=0
                        while(sub+12<=limit) {
                            if(++chunks>10000)throw IOException("PROP DFF quá nhiều chunk")
                            val head=source.read(sub,12);val length=u64(head,4,false);val payload=sub+12;bound(payload,length,limit)
                            when(text(head,0)) {
                                "FS  "->{if(length<4)throw IOException("FS thiếu dữ liệu");rate=u32(source.read(payload,4),0,false)}
                                "CHNL"->{if(length<2)throw IOException("CHNL thiếu dữ liệu");val b=source.read(payload,2);channels=((b[0].toInt()and 255)*256+(b[1].toInt()and 255)).toLong()}
                                "CMPR"->{if(length<4)throw IOException("CMPR thiếu dữ liệu");compression=text(source.read(payload,4),0)}
                            }
                            sub=Math.addExact(payload,Math.addExact(length,length%2))
                        }
                    }
                }
                "DST "->throw UnsupportedDsdException("Chưa hỗ trợ DFF nén DST")
                "DSD "->{
                    supported(channels,rate)
                    if(compression=="DST ")throw UnsupportedDsdException("Chưa hỗ trợ DFF nén DST")
                    if(compression!="DSD ")throw UnsupportedDsdException("DFF cần CMPR DSD không nén trước data")
                    if(size<1 || size%channels!=0L)throw IOException("Vùng DSDIFF không thẳng hàng channel")
                    return DsdHeader(DsdKind.DFF,channels.toInt(),rate.toInt(),Math.multiplyExact(size/channels,8),false,0,body,size)
                }
            }
            offset=Math.addExact(body,Math.addExact(size,size%2))
        }
        throw IOException("Quá nhiều chunk hoặc thiếu DSD data")
    }
    private fun supported(channels:Long,rate:Long) {
        if(channels !in 1..2)throw UnsupportedDsdException("Chỉ hỗ trợ DSD mono/stereo")
        if(rate !in listOf(2822400L,5644800L,11289600L))throw UnsupportedDsdException("Chỉ hỗ trợ DSD64/128/256 họ 44,1kHz; chưa hỗ trợ DSD512")
    }
    private fun bound(start:Long,size:Long,end:Long) {if(start<0 || size<0 || start>end || size>end-start)throw IOException("Phạm vi header DSD vượt nguồn")}
    private fun text(bytes:ByteArray,offset:Int)=String(bytes,offset,4,Charsets.US_ASCII)
    private fun u32(bytes:ByteArray,offset:Int,le:Boolean):Long=ByteBuffer.wrap(bytes,offset,4).order(if(le)ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).int.toLong()and 0xffffffffL
    private fun u64(bytes:ByteArray,offset:Int,le:Boolean):Long=ByteBuffer.wrap(bytes,offset,8).order(if(le)ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).long.also{if(it<0)throw IOException("Header DSD uint64 vượt giới hạn")}
}
