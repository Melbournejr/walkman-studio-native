// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import android.app.Application
import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import com.walkmanstudio.nativeplayer.core.DspConfig
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

    private val playing = PlaybackUi(id = "1", title = "Silver Current", artist = "North Window",
        album = "Quiet Circuits", durationMs = 245000, positionMs = 73000, timelineKnown = true,
        seekEnabled = true, requested = true)
    private val signal = SignalUi(source = "FLAC · 24 bit · 96 kHz",
        decoded = "PCM · 24 bit · 96 kHz", output = "PCM · 48 kHz · 2 kênh",
        outputDevice = "AudioTrack")
    private val specs = listOf("FLAC", "24 bit / 96 kHz")

    @Test fun modernPlayerUsesActualSeekabilityAndDispatchesActions() {
        var requestedAction = ""
        compose.setContent {
            StudioTheme("dark") {
                Surface(Modifier.size(390.dp, 820.dp)) {
                    MioNowPlaying(playing.copy(seekEnabled = false), specs,
                        CassetteActions(sound = { requestedAction = "audio" },
                            toggle = { requestedAction = "toggle" }), canFavorite = true) { FixtureCover(0, it) }
                }
            }
        }
        compose.onNodeWithText("Silver Current").assertIsDisplayed()
        compose.onNodeWithContentDescription("Tua bài hát").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Tạm dừng").performClick()
        assertEquals("toggle", requestedAction)
        compose.onNodeWithContentDescription("Audio Lab").performScrollTo().performClick()
        assertEquals("audio", requestedAction)
    }

    @Test fun captureAllReferenceScreens() {
        var mode by mutableStateOf("dark")
        var pane by mutableStateOf(MioDestination.PLAYER)
        var retro by mutableStateOf(false)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            StudioTheme(mode) {
                val size = if (mode == "dark") 390.dp to 820.dp else 360.dp to 690.dp
                Surface(Modifier.size(size.first, size.second).testTag("mio-preview")) {
                    if (retro) {
                        CassettePlayerScreen(WalkmanTheme.MIO, CassetteScreenState(
                            playing.title, playing.artist, playing.album, playing.positionMs, playing.durationMs,
                            playing = true, requested = true, queueIndex = 1, queueCount = 4, specs = specs),
                            CassetteActions(), visible = false)
                    } else Column {
                        MioHeader(pane.label, centered = pane == MioDestination.PLAYER,
                            back = if (pane == MioDestination.PLAYER) ({}) else null) {
                            if (pane == MioDestination.LIBRARY) IconButton(onClick = {}) {
                                StudioIcon(R.drawable.ic_mio_search, "Tìm bài hát")
                            }
                            if (pane == MioDestination.LIBRARY || pane == MioDestination.PLAYER) IconButton(onClick = {}) {
                                StudioIcon(R.drawable.ic_more, "Tùy chọn")
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            when (pane) {
                                MioDestination.PLAYER -> MioNowPlaying(playing, specs, CassetteActions(),
                                    canFavorite = true) { FixtureCover(if (mode == "dark") 0 else 3, it) }
                                MioDestination.LIBRARY -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    MioLibraryTabs("albums") {}
                                    val fixtureAlbums = listOf("Quiet Circuits" to "North Window",
                                        "Evergreen" to "Mara Vale", "Ribbons" to "Blue Avenue", "April Air" to "Amber Field")
                                    repeat(2) { row ->
                                        MioAlbumRow { column, modifier ->
                                            val index = row * 2 + column
                                            val (title, artist) = fixtureAlbums[index]
                                            Box(modifier) { MioAlbumTile(title, artist, {}) { FixtureCover(index, it) } }
                                        }
                                    }
                                }
                                MioDestination.AUDIO -> Box(Modifier.padding(horizontal = MioSpacing.gutter, vertical = 10.dp)) {
                                    MioAudioOverview(signal, null, DspConfig(sourceDirect = false), UsbSettingsUi()) {}
                                }
                                MioDestination.SETTINGS -> MioSettingsIndex {}
                            }
                        }
                        if (pane == MioDestination.LIBRARY) MioMiniPlayer(playing, {}, {}, {}) { FixtureCover(0, it) }
                        if (pane != MioDestination.PLAYER) MioNavigation(pane) { pane = it }
                    }
                }
            }
        }
        val names = listOf("now-playing", "library", "audio-lab", "settings")
        for (scheme in listOf("dark", "light")) {
            MioDestination.entries.forEach { destination ->
                compose.runOnIdle { mode = scheme; pane = destination }
                val name = when (destination) {
                    MioDestination.PLAYER -> names[0]
                    MioDestination.LIBRARY -> names[1]
                    MioDestination.AUDIO -> names[2]
                    MioDestination.SETTINGS -> names[3]
                }
                savePreview("$name-$scheme", view)
            }
        }
        compose.runOnIdle { mode = "dark"; retro = true }
        compose.onNodeWithText("Retro Mode").assertIsDisplayed()
        savePreview("retro-cassette", view)
    }

    @Composable
    private fun FixtureCover(index: Int, modifier: Modifier) {
        val palette = listOf(
            listOf(0xFF3B557C, 0xFFE7A18B, 0xFF293546, 0xFF182731),
            listOf(0xFF8498A3, 0xFF50666C, 0xFF26473F, 0xFF152F2C),
            listOf(0xFF322D4A, 0xFFE6A26B, 0xFFB96742, 0xFF25344E),
            listOf(0xFF7A7350, 0xFFF1C56C, 0xFF57472B, 0xFF2C3925),
        )[index].map { Color(it) }
        Canvas(modifier.clip(RoundedCornerShape(12.dp))) {
            drawRect(Brush.verticalGradient(listOf(palette[0], palette[1], palette[3]),
                endY = size.height))
            repeat(5) { layer ->
                val horizon = size.height * (.40f + layer * .095f)
                val land = Path().apply {
                    moveTo(0f, horizon)
                    for (point in 1..32) {
                        val x = point / 32f
                        val ridge = kotlin.math.sin(x * (5f + layer) + layer * 1.6f) +
                            .35f * kotlin.math.sin(x * 24f + layer)
                        lineTo(x * size.width, horizon + ridge * size.height * .09f)
                    }
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(land, palette[2 + layer % 2].copy(alpha = .48f + layer * .09f))
            }
            repeat(35) { line ->
                val y = size.height * (.72f + line * .008f)
                val x = (line * 73 % 100) / 100f * size.width
                drawLine(palette[1].copy(alpha = .24f), Offset(x, y), Offset(x + size.width * .17f, y), 1f)
            }
        }
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
