// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Delegates volume UI to Android; no app gain, DSP or playback-route changes. */
@Composable
internal fun SystemVolumeButton() {
    val context=LocalContext.current
    OutlinedButton(onClick={
        val intent=Intent(if(Build.VERSION.SDK_INT>=29) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS)
        try { context.startActivity(intent) }
        catch (_: ActivityNotFoundException) {
            try { context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }
            catch (_: ActivityNotFoundException) { /* OEM has no sound-settings handler; hardware volume keys remain available. */ }
        }
    }, modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Âm lượng hệ thống")}
}
