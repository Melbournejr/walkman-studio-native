// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import android.app.Application
import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w420dp-h920dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MioScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun modernPlayerSeparatesQueueAndAudioAndUsesActualSeekability() {
        var requestedAction = ""
        lateinit var view: View
        val state = PlaybackUi(id = "1", title = "Silver Current", artist = "North Window",
            album = "Quiet Circuits", durationMs = 245000, positionMs = 73000, timelineKnown = true, seekEnabled = false)
        compose.setContent {
            view = LocalView.current
            StudioTheme("dark") {
                Surface(Modifier.size(400.dp, 840.dp).testTag("mio-preview")) {
                    MioNowPlaying(state, listOf("FLAC", "96 kHz", "24 bit"),
                        CassetteActions(queue = { requestedAction = "queue" }, sound = { requestedAction = "audio" }),
                        volume = {}, sleep = {}) { TrackCover(null, it, large = true) }
                }
            }
        }
        compose.onNodeWithText("Silver Current").assertIsDisplayed()
        compose.onNodeWithText("FLAC · 96 kHz · 24 bit").assertIsDisplayed()
        compose.onNodeWithContentDescription("Tua bài hát").assertIsNotEnabled()
        compose.onNodeWithText("Hàng đợi").performScrollTo().performClick()
        assertEquals("queue", requestedAction)
        compose.onNodeWithText("Audio Lab").performScrollTo().performClick()
        assertEquals("audio", requestedAction)
        savePreview("now-playing-dark", view)
    }

    @Test fun settingsNavigationAndAudioStagesExposeDistinctSemantics() {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            var section by remember { mutableStateOf(MioSettingsSection.APPEARANCE) }
            StudioTheme("light") {
                Surface(Modifier.size(400.dp, 650.dp).testTag("mio-preview")) {
                    Column {
                        MioSettingsTabs(section) { section = it }
                        if (section == MioSettingsSection.AUDIO) {
                            Box(Modifier.padding(MioSpacing.gutter)) {
                                MioSignalPath(SignalUi(source = "FLAC · 96 kHz · 24 bit",
                                    decoded = "PCM Float32 · 96000 Hz", output = "PCM Float32 · 96000 Hz · 2 kênh",
                                    outputDevice = "USB DAC", dspBypassActive = true), null, true)
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Giao diện").assertIsSelected()
        compose.onNodeWithText("Âm thanh").performClick().assertIsSelected()
        listOf("Nguồn", "Giải mã", "Xử lý DSP", "Ngõ ra Android", "USB DAC").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        savePreview("audio-lab-light", view)
    }

    @Test fun primarySecondaryAndActionColorsMeetTextContrast() {
        for (palette in listOf(StudioDark, StudioLight)) {
            assertTrue(contrast(palette.onSurface, palette.surface) >= 4.5)
            assertTrue(contrast(palette.onSurfaceVariant, palette.surface) >= 4.5)
            assertTrue(contrast(palette.onPrimary, palette.primary) >= 4.5)
        }
    }

    private fun contrast(first: Color, second: Color): Double {
        val a = first.luminance().toDouble()
        val b = second.luminance().toDouble()
        return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
    }

    private fun savePreview(name: String, view: View) {
        val output = File("build/reports/mio-previews").apply { mkdirs() }
        val image = HostViewCapture.capture(compose, "mio-preview", view)
        File(output, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }
}
