// SPDX-License-Identifier: GPL-3.0-or-later
// Minimal synthetic files, written to the DSF/DSDIFF container specifications.
package com.walkmanstudio.nativeplayer.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object DsdTestFiles {
    fun dsfHeader(rate:Int,channels:Int,samples:Long,block:Int=4096,lsb:Boolean=true):ByteArray {
        val bytes=(samples+7)/8;val audio=((bytes+block-1)/block)*block*channels
        return ByteBuffer.allocate(92).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("DSD ".toByteArray());putLong(28);putLong(92+audio);putLong(0)
            put("fmt ".toByteArray());putLong(52);putInt(1);putInt(0);putInt(if(channels==1)1 else 2);putInt(channels);putInt(rate);putInt(if(lsb)1 else 8);putLong(samples);putInt(block);putInt(0)
            put("data".toByteArray());putLong(audio+12)
        }.array()
    }
    fun dsf(rate:Int,planes:Array<ByteArray>,samples:Long=planes[0].size.toLong()*8,block:Int=4096,lsb:Boolean=true):ByteArray {
        val header=dsfHeader(rate,planes.size,samples,block,lsb)
        val size=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getLong(12).toInt()
        return ByteArray(size){0x99.toByte()}.also { out ->
            header.copyInto(out)
            planes.forEachIndexed { channel,data -> data.forEachIndexed { i,byte -> out[92+(i/block)*block*planes.size+channel*block+i%block]=byte } }
        }
    }
    private fun chunk(id:String,data:ByteArray):ByteArray=ByteBuffer.allocate(12+data.size+(data.size%2)).order(ByteOrder.BIG_ENDIAN).apply {put(id.toByteArray());putLong(data.size.toLong());put(data)}.array()
    fun dffHeader(rate:Int,channels:Int,audio:Long,compression:String="DSD "):ByteArray {
        val fs=chunk("FS  ",ByteBuffer.allocate(4).putInt(rate).array())
        val ch=chunk("CHNL",ByteBuffer.allocate(2+channels*4).apply {putShort(channels.toShort());repeat(channels){put(if(it==0)"SLFT".toByteArray() else "SRGT".toByteArray())}}.array())
        val cmpr=chunk("CMPR",compression.toByteArray()+byteArrayOf(0))
        val prop=chunk("PROP","SND ".toByteArray()+fs+ch+cmpr)
        val bytes=16+prop.size+12
        return ByteBuffer.allocate(bytes).order(ByteOrder.BIG_ENDIAN).apply {put("FRM8".toByteArray());putLong(bytes+audio+audio%2-12);put("DSD ".toByteArray());put(prop);put("DSD ".toByteArray());putLong(audio)}.array()
    }
    fun dff(rate:Int,planes:Array<ByteArray>):ByteArray {
        require(planes.all{it.size==planes[0].size})
        val size=planes.size*planes[0].size;val header=dffHeader(rate,planes.size,size.toLong())
        return header+ByteArray(size+size%2){i -> if(i<size)planes[i%planes.size][i/planes.size] else 0}
    }
    fun reverseBits(byte:Byte):Byte = (Integer.reverse(byte.toInt()and 255) ushr 24).toByte()
}
