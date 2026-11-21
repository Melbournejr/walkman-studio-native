// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
        seekEnabled = true, requested = true, playbackState = androidx.media3.common.Player.STATE_READY)
    private val referenceTrack = playing.copy(title = "First Light", artist = "Haru Takahashi",
        album = "The Silent Season", durationMs = 296000, positionMs = 84000)
    private val signal = SignalUi(source = "FLAC · 24 bit · 96 kHz",
        decoded = "PCM · 24 bit · 96 kHz", output = "PCM · 48 kHz · 2 kênh",
        outputDevice = "AudioTrack")
    private val specs = listOf("FLAC", "24 bit", "96 kHz")

    @Test fun modernPlayerUsesActualSeekabilityAndDispatchesActions() {
        var requestedAction = ""
        compose.setContent {
            StudioTheme("dark") {
                Surface(Modifier.size(390.dp, 820.dp)) {
                    MioNowPlaying(playing.copy(seekEnabled = false), specs,
                        CassetteActions(sound = { requestedAction = "audio" },
                            toggle = { requestedAction = "toggle" })) { FixtureCover(0, it) }
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
        var compact by mutableStateOf(false)
        var pane by mutableStateOf(MioDestination.PLAYER)
        var extra by mutableStateOf("")
        var selectedQueueIndex = -1
        var removedQueueIndex = -1
        var stopped = false
        var toggled = false
        var seekPosition = -1L
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            StudioTheme(mode) {
                val size = if (mode == "dark" && !compact) 390.dp to 844.dp else 360.dp to 800.dp
                Surface(Modifier.size(size.first, size.second).testTag("mio-preview")) {
                    if (extra == "queue") {
                        MioQueue(listOf(
                            MioQueueItem("0", playing.title, playing.artist),
                            MioQueueItem("1", "Evergreen", "Mara Vale"),
                            MioQueueItem("2", "Ribbons", "Blue Avenue"),
                            MioQueueItem("3", "April Air", "Amber Field"),
                        ), 0, {}, { selectedQueueIndex = it }, { removedQueueIndex = it }) { item, modifier -> FixtureCover(item.id.toInt(), modifier) }
                    } else if (extra == "equalizer") {
                        var equalizer by remember { mutableStateOf(DspConfig(sourceDirect = false,
                            bands = listOf(-1.0, -.5, 2.0, 4.0, 1.0, -1.5, -1.0, 2.4, 4.8, 3.0))) }
                        MioEqualizer(equalizer, {}, { equalizer = it }) {}
                    } else if (extra == "retro") {
                        CassettePlayerScreen(WalkmanTheme.TPS_L2, CassetteScreenState(
                            referenceTrack.title, referenceTrack.artist, referenceTrack.album, referenceTrack.positionMs, referenceTrack.durationMs,
                            playing = true, requested = true, queueIndex = 1, queueCount = 4, specs = specs),
                            CassetteActions(stop = { stopped = true }, toggle = { toggled = true },
                                seek = { seekPosition = it }), visible = false)
                    } else Column(Modifier.then(if (pane == MioDestination.PLAYER || pane == MioDestination.LIBRARY)
                        Modifier.mioGraphite() else Modifier)) {
                        if (pane == MioDestination.PLAYER) MioNowHeader({}, false, true, {}) {
                            IconButton(onClick = {}) { StudioIcon(R.drawable.ic_more, "Tùy chọn") }
                        } else MioHeader(pane.label, editorial = pane == MioDestination.LIBRARY) {
                            if (pane == MioDestination.LIBRARY) IconButton(onClick = {}) {
                                StudioIcon(R.drawable.ic_mio_search, "Tìm bài hát")
                            }
                            if (pane == MioDestination.LIBRARY || pane == MioDestination.PLAYER) IconButton(onClick = {}) {
                                StudioIcon(R.drawable.ic_more, "Tùy chọn")
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            when (pane) {
                                MioDestination.PLAYER -> MioNowPlaying(referenceTrack, specs, CassetteActions(),
                                    highResolutionSource = true) { ReferenceCover("now-playing", it) }
                                MioDestination.LIBRARY -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    item { MioLibraryTabs("albums") {} }
                                    val fixtureAlbums = listOf("The Silent Season" to "Haru Takahashi",
                                        "Blue Ridge" to "Norah Elm", "Chroma" to "Luca Moretti", "Mono No Aware" to "Sakura Ensemble",
                                        "Silver Current" to "North Window", "Quiet Circuits" to "Amber Field")
                                    items(3) { row ->
                                        MioAlbumRow { column, modifier ->
                                            val index = row * 2 + column
                                            val (title, artist) = fixtureAlbums[index]
                                            Box(modifier) { MioAlbumTile(title, artist, mioAlbumArtworkRatio(row), {}, {}) { ReferenceCover("album-$index", it) } }
                                        }
                                    }
                                }
                                MioDestination.AUDIO -> Box(Modifier.padding(horizontal = MioSpacing.gutter, vertical = 10.dp)) {
                                    MioAudioOverview(signal, null, DspConfig(sourceDirect = false), UsbSettingsUi()) {}
                                }
                                MioDestination.SETTINGS -> MioSettingsIndex {}
                            }
                        }
                        if (pane == MioDestination.LIBRARY) MioMiniPlayer(playing.copy(positionMs = 122500), {}, {}, {}) {
                            ReferenceCover("album-0", it)
                        }
                        if (pane != MioDestination.PLAYER) MioNavigation(pane) { pane = it }
                    }
                }
            }
        }
        for (scheme in listOf("dark", "light")) {
            MioDestination.entries.forEach { destination ->
                compose.runOnIdle { mode = scheme; pane = destination }
                val name = when (destination) {
                    MioDestination.PLAYER -> "now-playing"
                    MioDestination.LIBRARY -> "library"
                    MioDestination.AUDIO -> "audio-lab"
                    MioDestination.SETTINGS -> "settings"
                }
                savePreview("$name-$scheme", view)
            }
        }
        compose.runOnIdle { mode = "dark"; extra = "retro" }
        compose.onNodeWithText("Retro Studio").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dừng phát").assertIsDisplayed()
        savePreview("retro-cassette", view)
        compose.onNodeWithContentDescription("Dừng phát").performClick()
        assertTrue(stopped)
        compose.onNodeWithContentDescription("Tạm dừng").performClick()
        assertTrue(toggled)
        compose.onNodeWithContentDescription("Cassette. Vuốt ngang để tua bài hát")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(.5f) }
        assertEquals(148000L, seekPosition)
        compose.runOnIdle { compact = true }
        savePreview("retro-cassette-compact", view)
        compose.runOnIdle { extra = ""; pane = MioDestination.PLAYER }
        savePreview("now-playing-compact", view)
        compose.runOnIdle { pane = MioDestination.LIBRARY }
        savePreview("library-compact", view)
        compose.runOnIdle { compact = false; extra = "queue" }
        compose.onNodeWithContentDescription("Bài hiện tại").assertIsDisplayed()
        savePreview("queue-dark", view)
        compose.onNodeWithText("Evergreen").performClick()
        compose.onNodeWithContentDescription("Bỏ Ribbons khỏi hàng đợi").performClick()
        assertEquals(1, selectedQueueIndex)
        assertEquals(2, removedQueueIndex)
        compose.runOnIdle { extra = "equalizer" }
        compose.onNodeWithContentDescription("Xử lý DSP (tắt Source Direct)").assertIsOn()
        savePreview("equalizer-dark", view)
        compose.onNodeWithContentDescription("Xử lý DSP (tắt Source Direct)").performClick().assertIsOff()
    }

    @Composable
    private fun ReferenceCover(name: String, modifier: Modifier) {
        val image = remember(name) {
            requireNotNull(javaClass.getResourceAsStream("/mio-polish/$name.jpg")).use {
                BitmapFactory.decodeStream(it).asImageBitmap()
            }
        }
        Image(image, null, modifier.clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
    }

    @Composable
    private fun FixtureCover(index: Int, modifier: Modifier) {
        val palette = listOf(
            listOf(0xFF3B557C, 0xFFE7A18B, 0xFF293546, 0xFF182731),
            listOf(0xFF8498A3, 0xFF50666C, 0xFF26473F, 0xFF152F2C),
            listOf(0xFF322D4A, 0xFFE6A26B, 0xFFB96742, 0xFF25344E),
            listOf(0xFF99717B, 0xFFF1BBB2, 0xFF503B4B, 0xFF373243),
            listOf(0xFF445779, 0xFFF99951, 0xFF44556B, 0xFF1D2A3D),
            listOf(0xFF326185, 0xFFBC945D, 0xFF554030, 0xFF2A2832),
        )[index].map { Color(it) }
        Canvas(modifier.clip(RoundedCornerShape(12.dp))) {
            drawRect(Brush.verticalGradient(listOf(palette[0], palette[1], palette[3]),
                endY = size.height))
            if (index == 0) {
                val height = size.height
                val width = size.width
                val ridges = listOf(0f to .67f, .15f to .63f, .33f to .51f, .52f to .32f,
                    .61f to .39f, .76f to .50f, 1f to .57f)
                val silhouette = Path().apply {
                    ridges.forEachIndexed { i, (x, y) ->
                        if (i == 0) moveTo(x * width, y * height) else lineTo(x * width, y * height)
                    }
                    lineTo(width, height * .74f); lineTo(0f, height * .74f); close()
                }
                drawPath(silhouette, Brush.verticalGradient(listOf(Color(0xFF72627B), Color(0xFF23374D)),
                    startY = height * .32f, endY = height * .73f))
                repeat(10) { ridge ->
                    val x = (ridge + 1) / 11f
                    val crest = Offset(width * (.512f + ridge * .0017f), height * (.331f + ridge * .004f))
                    val end = Offset(width * x, height * (.54f + (ridge * 7 % 11) * .009f))
                    drawLine(Color(0xFFF4C5B0).copy(alpha = .2f + x * .36f), crest, end, .7f + x * size.width / 195f)
                }
            }
            if (index == 2) {
                repeat(54) { stripe ->
                    val x = stripe * size.width / 37f
                    val curve = Path().apply {
                        moveTo(x - size.width * .55f, 0f)
                        cubicTo(x + size.width * .28f, size.height * .35f,
                            x - size.width * .45f, size.height * .60f, x - size.width * .06f, size.height)
                    }
                    drawPath(curve, palette[stripe % 3].copy(alpha = .65f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(size.width / 160f))
                }
                return@Canvas
            }
            repeat(5) { layer ->
                val horizon = size.height * ((if (index == 0) .72f else .40f) + layer * (if (index == 0) .041f else .095f))
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

    @Test fun spriteReelsMoveIndependentlyOfHousing() {
        val angle = mutableDoubleStateOf(0.0)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            StudioTheme("dark") {
                Surface(Modifier.size(390.dp, 844.dp).testTag("mio-preview")) {
                    MioCassetteSprites(CassetteScreenState(title = "Bên kia mùa mưa"), CassetteActions(),
                        angle, .25f, Modifier) {}
                }
            }
        }
        fun patch(image: Bitmap, x: Int, y: Int): List<Int> =
            (0 until 22).flatMap { dy -> (0 until 22).map { dx -> image.getPixel(x + dx, y + dy) } }
        val frameBefore = HostViewCapture.capture(compose, "mio-preview", view)
        compose.runOnIdle { angle.doubleValue = 42.0 }
        val frameAfter = HostViewCapture.capture(compose, "mio-preview", view)
        assertNotEquals(patch(frameBefore, 95, 295), patch(frameAfter, 95, 295))
        assertNotEquals(patch(frameBefore, 260, 295), patch(frameAfter, 260, 295))
        assertEquals(patch(frameBefore, 102, 158), patch(frameAfter, 102, 158))
        frameBefore.recycle()
        frameAfter.recycle()
    }

    @Config(qualifiers = "w500dp-h1100dp-mdpi")
    @Test fun savedBlueCassetteOpensStudioAtTheApprovedPortraitScale() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE).edit()
            .putString("playerDisplay", "cassette").apply()
        compose.setContent {
            StudioTheme("dark") {
                Surface(Modifier.size(480.dp, 1040.dp)) {
                    CassettePlayerScreen(
                        WalkmanTheme.TPS_L2,
                        CassetteScreenState("Bên kia mùa mưa", durationMs = 296000, positionMs = 84000,
                            requested = true, timelineKnown = true, seekEnabled = true),
                        CassetteActions(stop = {}), visible = false,
                        mode = PlayerDisplaySettings.read(context),
                    )
                }
            }
        }

        val deck = compose.onNodeWithTag("mio-case", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val keys = compose.onNodeWithTag("mio-key-row", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Cassette width stays at the approved portrait size", deck.width <= 380f)
        assertTrue("The hardware begins below the header without a tall-screen void", deck.top <= 110f)
        assertTrue("The transport remains inside the cassette case", keys.top > deck.top && keys.bottom < deck.bottom)
        compose.onNodeWithText("Retro Studio").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dừng phát").assertIsDisplayed()
    }

    @Config(qualifiers = "w844dp-h390dp-mdpi")
    @Test fun landscapeStudioKeepsCassetteAndTransportReachableWithSavedPalette() {
        var stopInvoked = false
        compose.setContent {
            CassettePlayerScreen(WalkmanTheme.TPS_L2, CassetteScreenState(title = "Mưa chiều"),
                CassetteActions(stop = { stopInvoked = true }), visible = false)
        }
        compose.onNodeWithText("Retro Studio").assertIsDisplayed()
        compose.onNodeWithTag("mio-window", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Dừng phát").performScrollTo().performClick()
        assertTrue(stopInvoked)
    }

    @Test fun compactPlayerKeepsActionsAtLargeFontWithMissingArtwork() {
        compose.setContent {
            val deviceDensity = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(deviceDensity.density, fontScale = 1.65f)) {
                StudioTheme("light") {
                    Surface(Modifier.size(360.dp, 690.dp)) {
                        MioNowPlaying(playing.copy(title = "Những ngày mưa vẫn rơi trên con phố dài"),
                            specs, CassetteActions()) { TrackCover(null, it, large = true) }
                    }
                }
            }
        }
        compose.onNodeWithText("Những ngày mưa vẫn rơi trên con phố dài").assertIsDisplayed()
        compose.onNodeWithContentDescription("Tạm dừng").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Audio Lab").performScrollTo().assertIsDisplayed()
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

    private val geometryTags = mapOf(
        "now-playing-dark" to listOf("mio-cover", "mio-track-metadata", "mio-transport", "mio-format"),
        "library-dark" to listOf("mio-album-cover", "mio-mini-player", "mio-navigation"),
        "retro-cassette" to listOf("mio-case", "mio-window", "mio-key-row"),
    )

    private fun savePreview(name: String, view: View) {
        val output = File("build/reports/mio-previews").apply { mkdirs() }
        geometryTags[name]?.let { tags ->
            val root = compose.onNodeWithTag("mio-preview").fetchSemanticsNode().boundsInRoot
            val geometry = org.json.JSONObject()
            tags.forEach { tag ->
                val boxes = org.json.JSONArray()
                compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
                    val b = node.boundsInRoot
                    boxes.put(org.json.JSONArray(listOf(b.left - root.left, b.top - root.top,
                        b.right - root.left, b.bottom - root.top)))
                }
                geometry.put(tag, boxes)
            }
            File(output, "$name-geometry.json").writeText(geometry.toString(2))
        }
        val image = HostViewCapture.capture(compose, "mio-preview", view)
        File(output, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }
}
