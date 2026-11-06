// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback

import android.content.Context

/** Separate from DSP: DSD level remains selected when Source Direct bypasses the DSP chain. */
object DsdLevelSettings {
    fun read(context: Context): Int {
        val saved = context.getSharedPreferences("dsdLevel", Context.MODE_PRIVATE).getInt("gainDb", 0)
        return saved.takeIf { it in DsdLevelState.ALLOWED_GAINS_DB } ?: 0
    }
    fun write(context: Context, gainDb: Int) {
        require(gainDb in DsdLevelState.ALLOWED_GAINS_DB)
        context.getSharedPreferences("dsdLevel", Context.MODE_PRIVATE).edit().putInt("gainDb", gainDb).apply()
    }
}
