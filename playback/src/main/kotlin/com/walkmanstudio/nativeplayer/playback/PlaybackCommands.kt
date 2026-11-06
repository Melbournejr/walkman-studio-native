// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand
import androidx.media3.common.util.UnstableApi

@androidx.annotation.OptIn(UnstableApi::class)
object PlaybackCommands {
    const val PLAY_SELECTION = "walkman.play_selection"
    const val DSP = "walkman.dsp"
    const val DSD_LEVEL = "walkman.dsd_level"
    const val SLEEP = "walkman.sleep"
    fun command(action: String) = SessionCommand(action, Bundle.EMPTY)
}
