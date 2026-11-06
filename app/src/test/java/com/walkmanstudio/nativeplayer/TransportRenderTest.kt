// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.view.View
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows.shadowOf
import android.animation.ValueAnimator
import org.robolectric.util.ReflectionHelpers
import org.junit.Before
import org.junit.After
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.security.MessageDigest

/** Actual Compose/Skia host rendering, not Android-device FPS or GPU/CPU profiling. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class TransportRenderTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var hostView: View

    private var priorScale = 1f
    @Before fun reduceMotionInFrameworkFixture() {
        // Robolectric does not run Android's system settings observer. Simulate its real
        // ValueAnimator scale update, confined to the framework fixture, not app code.
        priorScale = ReflectionHelpers.getStaticField(ValueAnimator::class.java, "sDurationScale")
        ReflectionHelpers.setStaticField(ValueAnimator::class.java, "sDurationScale", 0f)
        assertFalse(ValueAnimator.areAnimatorsEnabled())
    }
    @After fun restoreFrameworkMotionScale() {
        ReflectionHelpers.setStaticField(ValueAnimator::class.java, "sDurationScale", priorScale)
    }

    @Test fun rendersEveryTransportAndThemeAndMeasuresControlledHostFrames() {
        var mode by mutableStateOf(NowPlayingMode.CASSETTE)
        var theme by mutableStateOf(WalkmanTheme.TPS_L2)
        var position by mutableLongStateOf(60_000)
        var lastSeek = -1L
        val seekingEvents = mutableListOf<Boolean>()
        compose.setContent {
            val view = LocalView.current
            SideEffect { hostView = view }
            MaterialTheme(colorScheme = theme.colors) {
                Column(Modifier.testTag("transport-scene").width(400.dp).height(480.dp).background(theme.body).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Đang phát · ${mode.label}", color = theme.ink)
                    NowPlayingVisual(mode, theme, "Nắng trên băng từ", "Nghệ sĩ thử nghiệm", position, 240_000,
                        playing = true, seeking = false, onSeekingChange = { seekingEvents += it }, onSeek = { lastSeek = it },
                        modifier = Modifier.width(352.dp).height(if (mode == NowPlayingMode.CASSETTE) 240.dp else 352.dp)) { art ->
                        Box(art.background(theme.accent), contentAlignment = Alignment.Center) { Text("ALBUM", color = theme.body) }
                    }
                    Text("1:00 / 4:00 · Bản render host", color = theme.ink)
                }
            }
        }
        val output = File("build/reports/stage8-render").apply { mkdirs() }
        val hashes = mutableSetOf<String>()
        for (palette in WalkmanTheme.entries) for (transport in listOf(NowPlayingMode.CASSETTE, NowPlayingMode.VINYL)) {
            compose.runOnIdle { theme = palette; mode = transport }
            val image = capture("transport-scene")
            assertEquals(400, image.width)
            assertEquals(480, image.height)
            assertTrue("Actual transport/text must render, not a uniform blank surface", diversity(image) > 24)
            val hash = fingerprint(image)
            assertTrue("Every palette/transport must visibly differ", hashes.add(hash))
            File(output, "${transport.id}-${palette.id}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // Real TalkBack action and touch gesture reach the same seek callback as the slider.
        val display = compose.onNodeWithContentDescription("Đĩa than. Vuốt ngang để tua bài hát")
        display.performSemanticsAction(SemanticsActions.SetProgress) { it(1.25f) }
        compose.runOnIdle { assertEquals(240_000L, lastSeek); lastSeek = -1L; seekingEvents.clear() }
        display.performTouchInput { swipeLeft() }
        compose.runOnIdle {
            assertTrue("Horizontal drag must seek backward within song bounds", lastSeek in 0L..59_999L)
            assertTrue("Touch seek must lift the tonearm", seekingEvents.contains(true))
            assertEquals(false, seekingEvents.last())
        }
        // Controlled progress changes force real geometry/layout/drawing; screenshot readback is included.
        // This deliberately profiles no audio, service, FIR, phone GPU or free-running display vsync.
        val frames = 60
        val measurements = mutableListOf<String>()
        for (transport in listOf(NowPlayingMode.CASSETTE, NowPlayingMode.VINYL)) {
            compose.runOnIdle { mode = transport; theme = WalkmanTheme.TPS_L2; position = 0 }
            capture("transport-scene") // warm-up native Skia software drawing
            val initial = fingerprint(capture("transport-scene"))
            val frameNanos = LongArray(frames)
            var last = initial
            repeat(frames) { frame ->
                val started = HostRenderClock.nanoTime()
                compose.runOnIdle { position = ((frame + 1) * 240_000L / frames) }
                val bitmap = capture("transport-scene")
                frameNanos[frame] = HostRenderClock.nanoTime() - started
                last = fingerprint(bitmap)
            }
            assertNotEquals("Progress must redraw actual reel packs/tonearm", initial, last)
            // Measurement validity replaces the unavailable JVM CPU-counter checks.
            // All geometry, image diversity, input and motion assertions remain unchanged.
            assertTrue("Every render/readback must have a positive monotonic elapsed time", frameNanos.all { it > 0 })
            val ordered = frameNanos.sorted()
            measurements += """{"mode":"${transport.id}","frames":$frames,"meanMsPerFrame":${frameNanos.average() / 1e6},"medianMsPerFrame":${(ordered[29] + ordered[30]) / 2e6},"p95MsPerFrame":${ordered[56] / 1e6},"minMsPerFrame":${ordered.first() / 1e6},"maxMsPerFrame":${ordered.last() / 1e6},"samplesMs":[${frameNanos.joinToString(",") { (it / 1e6).toString() }}]}"""
        }
        File(output, "host-render-metrics.json").writeText("""{"environment":"Robolectric 4.16.1 API35 native Skia, host JVM, View.draw into a software bitmap","clock":"System.nanoTime per render","workload":"60 controlled progress updates per mode; system reduced-motion static rotation; real progress-dependent geometry; includes Compose synchronization, bitmap allocation and native Skia View drawing; excludes pixel hashing and PNG encoding","deviceBenchmark":false,"cpuMeasured":false,"phoneCpuOrBatteryInferred":false,"steadyPlaybackAnimationFpsVerified":false,"results":[${measurements.joinToString(",")}]}
""")
    }

    @Test fun renderedRotationFreezesWhenPausedOrDisplayTurnsOff() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val power = app.getSystemService(PowerManager::class.java)
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        shadowOf(power).turnScreenOn(true)
        shadowOf(keyguard).setKeyguardLocked(false)
        ReflectionHelpers.setStaticField(ValueAnimator::class.java, "sDurationScale", 1f)
        assertTrue(ValueAnimator.areAnimatorsEnabled())
        var playing by mutableStateOf(true)
        var visible by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val view = LocalView.current
            SideEffect { hostView = view }
            MaterialTheme(colorScheme = WalkmanTheme.TPS_L2.colors) {
                NowPlayingVisual(NowPlayingMode.VINYL, WalkmanTheme.TPS_L2, "Kiểm tra chuyển động", "Test", 60_000, 240_000,
                    playing, false, {}, {}, Modifier.testTag("motion-scene").size(352.dp), visible=visible) { art ->
                    Box(art.background(WalkmanTheme.TPS_L2.accent), contentAlignment = Alignment.Center) { Text("ALBUM") }
                }
            }
        }
        fun image() = fingerprint(capture("motion-scene"))
        compose.mainClock.advanceTimeBy(64)
        val start = image()
        compose.mainClock.advanceTimeBy(1_000)
        assertNotEquals("Real frame-clock updates must rotate the vector disc and label", start, image())
        compose.runOnIdle { playing = false }
        compose.mainClock.advanceTimeBy(64)
        val paused = image()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("No frame changes while paused", paused, image())
        compose.runOnIdle { playing = true }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { visible = false }
        compose.mainClock.advanceTimeBy(64)
        val covered = image()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("No animation behind a modal overlay", covered, image())
        compose.runOnIdle { visible = true }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle {
            // Current Robolectric API updates the shared state and emits ACTION_SCREEN_OFF.
            shadowOf(power).turnScreenOn(false)
        }
        // Screen broadcasts use Android's Handler queue, not the Compose test clock.
        // Deliver the real registered receiver callback before measuring the settled frame.
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("Framework fixture must report a non-interactive display", power.isInteractive)
        assertTrue("Framework fixture must emit SCREEN_OFF", shadowOf(app as Application).broadcastIntents.any { it.action == Intent.ACTION_SCREEN_OFF })
        compose.mainClock.advanceTimeBy(64)
        val off = image()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("No animation after display-off broadcast", off, image())
        compose.runOnIdle { playing = false }
        compose.mainClock.advanceTimeBy(32)
    }

    private fun capture(tag: String): Bitmap {
        compose.waitForIdle()
        return HostViewCapture.capture(compose, tag, hostView)
    }

    private fun diversity(bitmap: Bitmap): Int {
        val values = mutableSetOf<Int>()
        for (y in 40 until bitmap.height - 40 step 4) for (x in 24 until bitmap.width - 24 step 4) values += bitmap.getPixel(x, y)
        return values.size
    }
    private fun fingerprint(bitmap: Bitmap): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val row = IntArray(bitmap.width)
        val bytes = ByteArray(bitmap.width * 4)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            row.forEachIndexed { x, value -> repeat(4) { byte -> bytes[x * 4 + byte] = (value ushr (byte * 8)).toByte() } }
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
