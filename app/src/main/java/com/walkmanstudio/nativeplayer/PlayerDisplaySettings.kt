// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import android.content.Context

object PlayerDisplaySettings {
    fun cassetteLandscape(context: Context): Boolean =
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getBoolean("cassetteLandscape", false)
    fun setCassetteLandscape(context: Context, enabled: Boolean) {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putBoolean("cassetteLandscape", enabled).apply()
    }
    fun read(context: Context) = NowPlayingMode.find(
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("playerDisplay", null)
    )
    fun write(context: Context, mode: NowPlayingMode) {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("playerDisplay", mode.id).apply()
    }
}
