// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PlaybackMemory(val ids: List<Long>, val index: Int, val positionMs: Long, val shuffle: Boolean = false, val repeat: Int = 0) {
    fun encode(): String = JSONObject().put("ids", JSONArray(ids)).put("index", index)
        .put("positionMs", positionMs).put("shuffle", shuffle).put("repeat", repeat).toString()
    companion object {
        const val MAX_QUEUE = 300
        fun decode(text: String?): PlaybackMemory? = try {
            val json = JSONObject(text ?: "")
            val values = json.getJSONArray("ids")
            require(values.length() in 1..MAX_QUEUE)
            val ids = (0 until values.length()).map { values.getLong(it).also { id -> require(id > 0) } }
            PlaybackMemory(ids, json.optInt("index").coerceIn(ids.indices), json.optLong("positionMs").coerceAtLeast(0), json.optBoolean("shuffle"), json.optInt("repeat").coerceIn(0, 2))
        } catch (_: Exception) { null }
        fun preferences(context: Context) = context.getSharedPreferences("playback-memory", Context.MODE_PRIVATE)
    }
}
