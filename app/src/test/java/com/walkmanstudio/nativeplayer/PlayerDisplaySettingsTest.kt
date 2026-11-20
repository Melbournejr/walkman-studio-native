// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Preferences are the boundary under test; production Application/DI startup is covered
// by MainActivitySmokeTest. A plain framework Application prevents a second global Koin.
@Config(sdk=[35], application=Application::class)
class PlayerDisplaySettingsTest {
    @Test fun cassetteLandscapeIsOptInPersistentAndOnlyLocksTheCassetteScreen() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("appearance",Context.MODE_PRIVATE).edit().clear().commit()
        assertFalse(PlayerDisplaySettings.cassetteLandscape(context))
        PlayerDisplaySettings.setCassetteLandscape(context,true)
        assertTrue(PlayerDisplaySettings.cassetteLandscape(context))
        for(mode in NowPlayingMode.entries) for(show in listOf(false,true)) {
            val expected=if(show && mode==NowPlayingMode.CASSETTE) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            assertEquals(expected,CassetteOrientation.requested(show,mode,PlayerDisplaySettings.cassetteLandscape(context)))
        }
        PlayerDisplaySettings.setCassetteLandscape(context,false)
        assertFalse(PlayerDisplaySettings.cassetteLandscape(context))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,CassetteOrientation.requested(true,NowPlayingMode.CASSETTE,false))
    }
    @Test fun defaultsToClassicAndRestoresAllModesWithoutChangingTheme() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val prefs=context.getSharedPreferences("appearance",Context.MODE_PRIVATE)
        prefs.edit().clear().putString("walkman",WalkmanTheme.RH1.id).commit()
        assertEquals(NowPlayingMode.CLASSIC,PlayerDisplaySettings.read(context))
        NowPlayingMode.entries.forEach { mode ->
            PlayerDisplaySettings.write(context,mode)
            assertEquals(mode,PlayerDisplaySettings.read(context))
            assertEquals(WalkmanTheme.RH1.id,prefs.getString("walkman",null))
        }
        prefs.edit().putString("playerDisplay","unknown-future-mode").commit()
        assertEquals(NowPlayingMode.CLASSIC,PlayerDisplaySettings.read(context))
    }
}
