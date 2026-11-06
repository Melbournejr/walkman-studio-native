// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class DsdLevelSettingsTest {
    @Test fun defaultsAndPersistsOnlySupportedLevelsIndependentlyOfDsp() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("dsdLevel",Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(0,DsdLevelSettings.read(context))
        for(db in listOf(0,3,6)) {
            DsdLevelSettings.write(context,db)
            assertEquals(db,DsdLevelSettings.read(context))
            assertTrue(DspSettings.read(context).sourceDirect)
        }
        assertThrows(IllegalArgumentException::class.java) {DsdLevelSettings.write(context,9)}
        assertEquals(6,DsdLevelSettings.read(context))
        context.getSharedPreferences("dsdLevel",Context.MODE_PRIVATE).edit().putInt("gainDb",9).commit()
        assertEquals(0,DsdLevelSettings.read(context))
    }
}
