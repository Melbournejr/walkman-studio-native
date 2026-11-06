// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.update

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.*
import com.walkmanstudio.nativeplayer.HostViewCapture
import com.walkmanstudio.nativeplayer.core.update.UpdateArtifact
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w1000dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalRoborazziApi::class)
class UpdateSettingsRenderTest {
    @get:Rule val compose=createComposeRule()
    @Test fun readyUpdateControlsRemainReachableAtNormalAndDoubleFont(){
        val context=ApplicationProvider.getApplicationContext<Application>();val updates=AppUpdates(context)
        context.getSharedPreferences("application-updates",0).edit().clear().commit()
        updates.store.ready(UpdateArtifact(107,"1.3.1",26,3,"0".repeat(64),"https://github.com/Melbournejr/walkman-studio-native/releases/download/stage11/walkman-native-test.apk","Bản thử giao diện cập nhật; không phải APK cài đặt thật."))
        val model=UpdateViewModel(updates,context);var scale by mutableStateOf(1f);lateinit var host:View
        compose.setContent {
            val view=LocalView.current;SideEffect{host=view}
            CompositionLocalProvider(LocalDensity provides Density(1f,scale)){
                MaterialTheme {key(scale){Surface(Modifier.size(360.dp,800.dp).testTag("updates-scene")){
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)){UpdateSettings(model)}
                }}}
            }
        }
        val output=File("build/reports/stage10-screens").apply{mkdirs()}
        for(value in listOf(1f,2f)){
            compose.runOnIdle{scale=value};compose.waitForIdle()
            val install=compose.onNodeWithText("Cài bản cập nhật").performScrollTo().assertIsDisplayed().assertHasClickAction()
            val bounds=install.getUnclippedBoundsInRoot();assertTrue(bounds.left.value>=0);assertTrue(bounds.right.value<=360)
            for(label in listOf("Cài bản cập nhật","Kiểm tra bản mới")) {
                val action=compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().assertHasClickAction()
                val b=action.getUnclippedBoundsInRoot()
                assertTrue(b.left.value>=0 && b.right.value<=360)
                assertTrue(b.top.value>=0 && b.bottom.value<=800)
                assertTrue(b.bottom.value-b.top.value>=48)
            }
            install.performScrollTo()
            val bitmap=HostViewCapture.capture(compose,"updates-scene",host)
            assertEquals("Render fixture must include the real opaque settings surface",255,android.graphics.Color.alpha(bitmap.getPixel(0,0)))
            bitmap.captureRoboImage(File(output,"updates-font${if(value==1f)100 else 200}.png"),RoborazziOptions(taskType=RoborazziTaskType.Record,captureType=RoborazziOptions.CaptureType.Screenshot()));bitmap.recycle()
        }
    }
}
