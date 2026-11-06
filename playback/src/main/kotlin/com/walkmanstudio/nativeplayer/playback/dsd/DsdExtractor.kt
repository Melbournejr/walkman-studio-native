// SPDX-License-Identifier: GPL-3.0-or-later
// DSF/DFF physical layout and centered FIR windows derived from legacy dsd-format.js; see NOTICE.
package com.walkmanstudio.nativeplayer.playback.dsd

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.*
import com.walkmanstudio.nativeplayer.core.*
import com.walkmanstudio.nativeplayer.playback.DsdLevelState
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One decoder per active media source, fixed-size IO cache; no copy of the source file. */
@androidx.annotation.OptIn(UnstableApi::class)
class DsdExtractor(private val levelState:DsdLevelState=DsdLevelState(), private val event:(String,String)->Unit={_,_->}):Extractor {
    private lateinit var output:ExtractorOutput
    private lateinit var track:TrackOutput
    private var header:DsdHeader?=null
    private var fir:NativeDsdFir?=null
    private var frame=0L
    private var pendingTimeUs=0L
    private val headerCache=mutableMapOf<Pair<Long,Int>,ByteArray>()
    private var headerBytes=0
    private val cache=object:LinkedHashMap<Long,ByteArray>(16,0.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Long,ByteArray>?)=size>16}
    private var plane=ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    private var pcm=ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    private var pcmBytes=ByteArray(0)
    private var framesDecoded=0L
    private var firNs=0L
    private var windowNs=0L
    private var windowStart=0L
    private var released=false
    private var levelExceeded=0L
    override fun sniff(input:ExtractorInput):Boolean {
        val bytes=ByteArray(16)
        return try {
            if(!input.peekFully(bytes,0,16,true))false else String(bytes,0,4,Charsets.US_ASCII)=="DSD " ||
                (String(bytes,0,4,Charsets.US_ASCII)=="FRM8" && String(bytes,12,4,Charsets.US_ASCII)=="DSD ")
        }catch(_:EOFException){false}finally{input.resetPeekPosition()}
    }
    override fun init(output:ExtractorOutput){this.output=output;track=output.track(0,C.TRACK_TYPE_AUDIO);output.endTracks()}
    override fun read(input:ExtractorInput,seekPosition:PositionHolder):Int {
        try {
            if(header==null) {
                val parsed=DsdParser.parse(object:DsdBytes {
                    override val length=input.length.takeIf {it>=0}
                    override fun read(position:Long,length:Int):ByteArray {
                        val key=position to length
                        return headerCache[key] ?: run {
                            if(input.position!=position)throw NeedPosition(position)
                            if(headerBytes+length>512*1024)throw UnsupportedDsdException("Header DSD vượt512KiB")
                            ByteArray(length).also {input.readFully(it,0,length);headerCache[key]=it;headerBytes+=length}
                        }
                    }
                })
                fir=NativeDsdFir(parsed.ratio,parsed.lsbFirst)
                header=parsed;frame=parsed.frameAt(pendingTimeUs);headerCache.clear()
                track.format(Format.Builder().setId("dsd-pcm").setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_FLOAT)
                    .setSampleRate(DsdHeader.OUTPUT_RATE).setChannelCount(parsed.channels).setMaxInputSize(BLOCK_FRAMES*parsed.channels*4)
                    .setLabel("DSD${parsed.sampleRate/44100} → PCM").setCustomData(parsed).build())
                output.seekMap(object:SeekMap {
                    override fun isSeekable()=true // SAF transport reopens+skips if provider cannot seek; latency is not guaranteed.
                    override fun getDurationUs()=parsed.durationUs
                    override fun getSeekPoints(timeUs:Long):SeekMap.SeekPoints {
                        val f=parsed.frameAt(timeUs);return SeekMap.SeekPoints(SeekPoint(parsed.timeAt(f),parsed.physicalSeek(f)))
                    }
                })
                event("dsd_open","kind=${parsed.kind}; rate=${parsed.sampleRate}; channels=${parsed.channels}; samples=${parsed.sampleCount}; outputRate=88200; backend=C++; dsdGainDb=${levelState.gainDb}")
            }
            val meta=header!!
            if(frame>=meta.totalFrames)return Extractor.RESULT_END_OF_INPUT
            if(windowStart==0L)windowStart=System.nanoTime()
            val window=meta.window(frame,BLOCK_FRAMES)
            val inputSize=window.bytesPerChannel*meta.channels
            if(plane.capacity()<inputSize)plane=ByteBuffer.allocateDirect(inputSize).order(ByteOrder.nativeOrder())
            plane.clear();repeat(inputSize){plane.put(SILENCE)}
            for(range in meta.ranges(window)) {
                var copied=0
                while(copied<range.count) {
                    val physical=range.position+copied.toLong()*range.stride
                    val key=(physical-meta.dataOffset)/CACHE_CHUNK*CACHE_CHUNK+meta.dataOffset
                    val data=cache[key] ?: load(input,meta,key).also{cache[key]=it}
                    val offset=(physical-key).toInt()
                    val count=minOf(range.count-copied,(data.size-1-offset)/range.stride+1)
                    if(count<1)throw EOFException("DSD window chưa đọc đủ dữ liệu")
                    for(n in 0 until count)plane.put(range.destination+copied+n,data[offset+n*range.stride])
                    copied+=count
                }
            }
            val remainder=(meta.sampleCount%8).toInt()
            if(remainder!=0 && window.readEnd==meta.channelBytes) {
                val index=(meta.channelBytes-1-window.startByte).toInt()
                val mask=if(meta.lsbFirst)(1 shl remainder)-1 else (255 shl (8-remainder))and 255
                for(channel in 0 until meta.channels) {
                    val at=channel*window.bytesPerChannel+index
                    plane.put(at,((plane.get(at).toInt()and mask)or(SILENCE.toInt()and mask.inv())).toByte())
                }
            }
            val outputBytes=window.frames*meta.channels*4
            if(pcm.capacity()<outputBytes)pcm=ByteBuffer.allocateDirect(outputBytes).order(ByteOrder.nativeOrder())
            pcm.clear()
            val began=System.nanoTime();fir!!.decode(plane,window.bytesPerChannel,meta.channels,window.frames,pcm);firNs+=System.nanoTime()-began
            levelExceeded+=levelState.applyToDsdFloat(pcm,window.frames*meta.channels)
            if(pcmBytes.size<outputBytes)pcmBytes=ByteArray(outputBytes)
            pcm.position(0);pcm.get(pcmBytes,0,outputBytes)
            track.sampleData(ParsableByteArray(pcmBytes,outputBytes),outputBytes)
            track.sampleMetadata(meta.timeAt(frame),C.BUFFER_FLAG_KEY_FRAME,outputBytes,0,null)
            frame+=window.frames;framesDecoded+=window.frames;windowNs+=System.nanoTime()-windowStart;windowStart=0
            return Extractor.RESULT_CONTINUE
        }catch(needed:NeedPosition){seekPosition.position=needed.offset;return Extractor.RESULT_SEEK}
        catch(unsupported:UnsupportedDsdException){throw ParserException.createForUnsupportedContainerFeature(unsupported.message.orEmpty())}
    }
    private fun load(input:ExtractorInput,meta:DsdHeader,position:Long):ByteArray {
        if(input.position!=position) {
            val skip=position-input.position
            if(skip in 1..(256*1024).toLong())input.skipFully(skip.toInt()) else throw NeedPosition(position)
        }
        val length=minOf(CACHE_CHUNK.toLong(),meta.dataOffset+meta.dataSize-position).toInt()
        if(length<1)throw EOFException("DSD data hết trước window cần đọc")
        return ByteArray(length).also{input.readFully(it,0,length)}
    }
    override fun seek(position:Long,timeUs:Long) {
        pendingTimeUs=timeUs;header?.let{frame=it.frameAt(timeUs)}
        cache.clear();windowStart=0
    }
    override fun release() {
        if(released)return
        released=true;fir?.close();fir=null;cache.clear();headerCache.clear()
        val seconds=framesDecoded.toDouble()/88200
        event("dsd_stats","frames=$framesDecoded; firMs=${firNs/1e6}; windowMs=${windowNs/1e6}; firRtf=${if(seconds>0)firNs/1e9/seconds else 0.0}; windowRtf=${if(seconds>0)windowNs/1e9/seconds else 0.0}; dsdGainDbNow=${levelState.gainDb}; decodedSamplesOver0Dbfs=$levelExceeded; counterIncludesSeekRepeatsAndBufferedAhead=true; includesWindowIO=true; environment=runtime_device_or_host")
    }
    private class NeedPosition(val offset:Long):RuntimeException()
    companion object {const val BLOCK_FRAMES=2048;const val CACHE_CHUNK=65536;private val SILENCE=0x99.toByte()}
}
