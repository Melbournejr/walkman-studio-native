// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback

import android.content.Context
import android.os.Bundle
import com.walkmanstudio.nativeplayer.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

object DspSettings {
    fun encode(config: DspConfig): String = JSONObject().apply {
        put("direct", config.sourceDirect); put("bands", JSONArray(config.bands)); put("bass",config.clearBass)
        put("preamp",config.preamp);put("replay",config.replayGain.name);put("guard",config.preventClipping);put("dither",config.dither)
    }.toString()
    fun decode(raw: String?): DspConfig = try {
        if (raw == null || raw.length > 4096) DspConfig() else JSONObject(raw).let { j ->
            val bands=j.getJSONArray("bands");require(bands.length()==10)
            DspConfig(j.getBoolean("direct"),List(10){bands.getDouble(it)},j.getDouble("bass"),j.getDouble("preamp"),ReplayGainMode.valueOf(j.getString("replay")),j.getBoolean("guard"),if(j.has("dither"))j.getBoolean("dither")else true)
        }
    }catch (_: Exception){DspConfig()}
    fun read(context: Context) = decode(context.getSharedPreferences("dsp",Context.MODE_PRIVATE).getString("config",null))
    fun write(context: Context, config: DspConfig) { context.getSharedPreferences("dsp",Context.MODE_PRIVATE).edit().putString("config",encode(config)).apply() }
}

class DspState(initial: DspConfig) {
    val config=AtomicReference(initial)
    val liveTags=AtomicReference<Pair<String,ReplayGainInfo>?>(null)
    @Volatile var appliedGainDb=0.0
    @Volatile var clippedSamples=0L
}

fun Bundle?.replayGainInfo(): ReplayGainInfo {
    fun gain(key:String): Double? = this?.takeIf { it.containsKey(key) }?.getDouble(key)?.takeIf { it.isFinite() }
    return ReplayGainInfo(gain("trackGain"),gain("albumGain"),gain("trackPeak"),gain("albumPeak"))
}
