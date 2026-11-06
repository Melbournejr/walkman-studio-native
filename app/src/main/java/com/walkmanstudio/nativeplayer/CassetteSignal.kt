// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.media3.common.C
import java.math.BigDecimal

/** UI-only presentation of selected Media3 Format fields and observed AudioTrack telemetry.
 * Unknown values stay absent. Lossy codecs have no claimed source PCM bit depth.
 * The DSD identity comes from this app's extractor ID + header-derived Format label,
 * never a filename or potentially stale session source-description string.
 */
object CassetteSignal {
    private val dsdLabel=Regex("^DSD(64|128|256) → PCM$")
    private val audioMime=Regex("audio/[a-zA-Z0-9.+-]{1,50}")
    private val outputFormat=Regex("^(PCM(?:8|16|24|32)|PCM float32) · ([0-9]+) Hz · ([0-9]+) kênh · offload=(true|false)$")
    private val sourcePcmTypes=setOf("audio/raw","audio/flac","audio/alac")

    fun dsdMultiplier(id:String?,mime:String?,labels:List<String>):Int? {
        if(id!="dsd-pcm" || mime!="audio/raw")return null
        return labels.firstNotNullOfOrNull { label -> dsdLabel.matchEntire(label)?.groupValues?.get(1)?.toIntOrNull() }
    }

    fun chips(signal:SignalUi, sourceDirect:Boolean=false, eqEnabled:Boolean=false):List<String> = buildList {
        val mime=signal.sourceMime?.takeIf { audioMime.matches(it) }
        val rate=signal.sourceRate?.takeIf { it>0 }
        val depth=pcmName(signal.sourcePcmEncoding)
        val dsd=signal.sourceDsdMultiplier?.takeIf { it in listOf(64,128,256) && mime=="audio/raw" && rate==88200 }
        if(dsd!=null) {
            add("DSD$dsd → PCM ${sampleRate(rate!!)}" + (depth?.let { " · $it" } ?: ""))
        } else {
            mime?.let { add(codecName(it)) }
            rate?.let { add(sampleRate(it)) }
            if(mime in sourcePcmTypes)depth?.let(::add)
        }
        // Media3 Format.averageBitrate/peakBitrate are container/decoder metadata in bits/s.
        // They are never computed from size/duration here. For AAC the container's esds
        // fields may be stale or nominal (e.g. 400118 b/s); no file-level evidence is
        // available to validate them, so neither average nor peak is displayed for AAC.
        if(mime!="audio/mp4a-latm") {
            signal.averageBitrate?.takeIf { it>0 }?.let { add("${scaled(it)} kb/s") }
                ?: signal.peakBitrate?.takeIf { it>0 }?.let { add("≤${scaled(it)} kb/s") }
        }
        outputFormat.matchEntire(signal.output)?.let { observed ->
            val outputRate=observed.groupValues[2].toIntOrNull()?.takeIf { it>0 }
            val channels=observed.groupValues[3].toIntOrNull()?.takeIf { it in 1..32 }
            if(outputRate!=null && channels!=null) {
                val encoding=if(observed.groupValues[1]=="PCM float32") "float" else observed.groupValues[1].removePrefix("PCM")+" bit"
                if(!sourceDirect) add("Ngõ ra · ${sampleRate(outputRate)} · $encoding")
            }
        }
        if(sourceDirect) add("Source Direct") else if(eqEnabled) add("EQ")
        signal.usbStatus?.let(::add)
    }

    private fun pcmName(value:Int?):String? = when(value) {
        C.ENCODING_PCM_8BIT -> "8 bit"
        C.ENCODING_PCM_16BIT -> "16 bit"
        C.ENCODING_PCM_24BIT -> "24 bit"
        C.ENCODING_PCM_32BIT -> "32 bit"
        C.ENCODING_PCM_FLOAT -> "float"
        else -> null
    }
    private fun sampleRate(rate:Int)="${scaled(rate)} kHz"
    private fun scaled(value:Int)=BigDecimal.valueOf(value.toLong()).movePointLeft(3).stripTrailingZeros().toPlainString().replace('.',',')
    private fun codecName(mime:String)=when(mime) {
        "audio/raw"->"PCM";"audio/flac"->"FLAC";"audio/mpeg"->"MP3";"audio/mp4a-latm"->"AAC"
        "audio/vorbis"->"Vorbis";"audio/opus"->"Opus";"audio/alac"->"ALAC"
        else->mime
    }
}
