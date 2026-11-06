// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** Roborazzi records real Compose pixels from the host's native-Skia View canvas.
 * Explicit Record mode emits reviewable PNGs; this is not a phone/GPU/FPS benchmark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=Application::class, qualifiers="w1000dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalRoborazziApi::class)
class Stage9bScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var hostView: View
    private var priorScale = 1f

    @Before fun stopMotionForDeterministicScreenshots() {
        priorScale = ReflectionHelpers.getStaticField(ValueAnimator::class.java, "sDurationScale")
        ReflectionHelpers.setStaticField(ValueAnimator::class.java, "sDurationScale", 0f)
        assertFalse(ValueAnimator.areAnimatorsEnabled())
    }
    @After fun restoreMotionSetting() {
        ReflectionHelpers.setStaticField(ValueAnimator::class.java, "sDurationScale", priorScale)
    }

    @Test fun recordsFull96CaseMatrixWithUnknownIdleAndSafeLandscape360() {
        val cases = buildList {
            for (landscape in listOf(false,true)) for (title in TitleKind.entries)
                for (dsd in listOf(false,true)) for (scale in listOf(1f,2f))
                    for (idle in listOf(false,true)) for (mode in listOf(NowPlayingMode.CASSETTE,NowPlayingMode.VINYL))
                        add(Scene(landscape,title,dsd,scale,idle,mode))
        }
        assertEquals(96,cases.size)
        var scene by mutableStateOf(cases.first())
        // One Activity/Compose host is kept for the entire matrix; individual scene
        // compositions are keyed so scroll/seek state never leaks between fixtures.
        compose.setContent {
            val view=LocalView.current
            SideEffect { hostView=view }
            CompositionLocalProvider(LocalDensity provides Density(1f,scene.fontScale)) {
                MaterialTheme(colorScheme=WalkmanTheme.TPS_L2.colors) {
                    Box(Modifier.fillMaxSize()) {
                        key(scene.id) {
                            Box(Modifier.size(scene.width.dp,scene.height.dp).background(Color.Black).testTag(SCENE)) {
                                CassettePlayerScreen(WalkmanTheme.TPS_L2,scene.state,CassetteActions(),
                                    Modifier.fillMaxSize().padding(top=24.dp,bottom=16.dp),mode=scene.mode)
                            }
                        }
                    }
                }
            }
        }
        val output=File("build/reports/stage9b-screens").apply { mkdirs() }
        val manifest=JSONArray()
        val options=RoborazziOptions(taskType=RoborazziTaskType.Record, captureType=RoborazziOptions.CaptureType.Screenshot())
        for (case in cases) {
            compose.runOnIdle { scene=case }
            compose.waitForIdle()
            val labelTag=if(case.mode==NowPlayingMode.CASSETTE) "cassette-label" else "vinyl-label"
            compose.onAllNodesWithText(case.state.title,useUnmergedTree=true).assertCountEquals(1)
            compose.onNodeWithTag(labelTag,useUnmergedTree=true).assertExists()
            compose.onAllNodesWithContentDescription("Thêm vào yêu thích").assertCountEquals(1)
            compose.onNodeWithContentDescription("Hàng đợi").assertExists()
            compose.onNodeWithContentDescription("Cài đặt âm thanh").assertExists()
            if (case.landscape) assertLandscapeContentsFit(case,labelTag)
            if (case.idle) {
                compose.onNodeWithTag("cassette-time-position").assertTextEquals("0:00")
                compose.onNodeWithTag("cassette-time-duration").assertTextEquals("--:--")
                compose.onNodeWithTag("cassette-progress").assertIsNotEnabled()
                val value=compose.onNodeWithTag("cassette-progress").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
                assertEquals("Unknown duration must never display a stale progress",0f,value.current,0f)
            } else {
                compose.onNodeWithTag("cassette-time-duration").assertTextEquals("4:00")
                compose.onNodeWithTag("cassette-progress").assertIsEnabled()
            }
            val bitmap=HostViewCapture.capture(compose,SCENE,hostView)
            assertEquals(case.width,bitmap.width);assertEquals(case.height,bitmap.height)
            assertTrue("Vector and text pixels must be rendered for ${case.id}",diversity(bitmap)>24)
            val file=File(output,"${case.id}.png")
            bitmap.captureRoboImage(file,options)
            assertTrue("Roborazzi must produce the PNG: ${case.id}",file.isFile && file.length()>1000)
            manifest.put(JSONObject().apply {
                put("file",file.name);put("width",case.width);put("height",case.height)
                put("orientation",if(case.landscape)"landscape" else "portrait")
                put("titleCase",case.titleKind.name);put("displayTitle",case.state.title)
                put("sourceFixture",if(case.dsd)"DSD128" else "FLAC PCM24/96k")
                put("fontScale",case.fontScale.toDouble());put("idleDurationUnknown",case.idle)
                put("mode",case.mode.id);put("safeInsetsDp",JSONObject().put("top",24).put("bottom",16))
                put("pngSha256",MessageDigest.getInstance("SHA-256").digest(file.readBytes()).hex())
            })
            bitmap.recycle()
        }
        assertEquals(96,manifest.length())
        assertEquals(96,output.listFiles { file -> file.extension=="png" }!!.size)
        File(output,"screenshots.json").writeText(JSONObject().apply {
            put("framework","Roborazzi 1.52.0 Bitmap.captureRoboImage Record")
            put("renderer","Compose View.draw / native Skia / Robolectric API35 / mdpi host")
            put("physicalDevice",false);put("fixtureMetadata",true)
            put("motion","system reduced-motion; deterministic static frame")
            put("cases",manifest)
        }.toString(2)+"\n")
    }

    @Test fun portraitFont200RetainsReachableControlsAndUnknownIdleCannotSeek() {
        var scene by mutableStateOf(Scene(false,TitleKind.LONG,true,2f,true,NowPlayingMode.CASSETTE))
        var seeks=0;var plays=0;var sounds=0;var queues=0
        val actions=CassetteActions(seek={seeks++},toggle={plays++},sound={sounds++},queue={queues++})
        compose.setContent {
            val view=LocalView.current
            SideEffect { hostView=view }
            CompositionLocalProvider(LocalDensity provides Density(1f,2f)) {
                key(scene.id) {
                    Box(Modifier.size(400.dp,800.dp).testTag(SCENE)) {
                        CassettePlayerScreen(WalkmanTheme.TPS_L2,scene.state,actions,Modifier.fillMaxSize(),mode=scene.mode)
                    }
                }
            }
        }
        for (mode in listOf(NowPlayingMode.CASSETTE,NowPlayingMode.VINYL)) {
            compose.runOnIdle { scene=scene.copy(mode=mode) }
            compose.onNodeWithContentDescription("Phát").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription("Cài đặt âm thanh").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription("Hàng đợi").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithTag("cassette-progress").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("cassette-art").performScrollTo().performTouchInput { swipeLeft() }
            compose.runOnIdle { assertEquals(0,seeks) }
        }
        compose.runOnIdle { assertEquals(2,plays);assertEquals(2,sounds);assertEquals(2,queues) }
    }

    @Test fun knownUnseekableTimelineAdvancesBothVisualsWithoutEnablingSeek() {
        var mode by mutableStateOf(NowPlayingMode.CASSETTE)
        var state by mutableStateOf(CassetteScreenState(title="Mưa chiều",positionMs=60_000,
            durationMs=240_000,seekEnabled=false,timelineKnown=true))
        var seeks=0
        compose.setContent {
            val view=LocalView.current
            SideEffect { hostView=view }
            key(mode) {
                Box(Modifier.size(400.dp,800.dp).testTag(SCENE)) {
                    CassettePlayerScreen(WalkmanTheme.TPS_L2,state,CassetteActions(seek={seeks++}),
                        Modifier.fillMaxSize(),mode=mode)
                }
            }
        }
        for (visual in listOf(NowPlayingMode.CASSETTE,NowPlayingMode.VINYL)) {
            compose.runOnIdle { mode=visual;state=state.copy(positionMs=60_000) }
            compose.onNodeWithTag("cassette-time-position").assertTextEquals("1:00")
            compose.onNodeWithTag("cassette-progress").assertIsNotEnabled()
            assertProgress("cassette-progress",.25f)
            assertProgress("cassette-art",.25f)
            compose.onNodeWithTag("cassette-art").performScrollTo().performTouchInput { swipeLeft() }
            compose.runOnIdle { assertEquals(0,seeks);state=state.copy(positionMs=120_000) }
            compose.onNodeWithTag("cassette-time-position").assertTextEquals("2:00")
            assertProgress("cassette-progress",.5f)
            assertProgress("cassette-art",.5f)
        }
    }

    @Test fun idleRestoreKeepsZeroProgressEvenWithRoomDurationAndStalePosition() {
        var mode by mutableStateOf(NowPlayingMode.CASSETTE)
        var duration by mutableStateOf(0L)
        compose.setContent {
            key(mode,duration) {
                Box(Modifier.size(400.dp,800.dp).testTag(SCENE)) {
                    CassettePlayerScreen(WalkmanTheme.TPS_L2,CassetteScreenState(title="Mưa chiều",
                        positionMs=120_000,durationMs=duration,seekEnabled=false,timelineKnown=false),
                        CassetteActions(),Modifier.fillMaxSize(),mode=mode)
                }
            }
        }
        for (visual in listOf(NowPlayingMode.CASSETTE,NowPlayingMode.VINYL)) for (stored in listOf(0L,240_000L)) {
            compose.runOnIdle { mode=visual;duration=stored }
            compose.onNodeWithTag("cassette-time-position").assertTextEquals("0:00")
            compose.onNodeWithTag("cassette-time-duration").assertTextEquals(if(stored>0)"4:00" else "--:--")
            compose.onNodeWithTag("cassette-progress").assertIsNotEnabled()
            assertProgress("cassette-progress",0f)
            assertProgress("cassette-art",0f)
            val packs=TransportGeometry.displayTapeRadii(.75f,false)
            assertEquals(packs.first,packs.second,0f)
        }
    }

    @Test fun landscapeFont200CanScrollEveryTechnicalChipFullyIntoItsViewport() {
        val scene=Scene(true,TitleKind.LONG,true,2f,false,NowPlayingMode.CASSETTE)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f,2f)) {
                Box(Modifier.size(900.dp,360.dp).testTag(SCENE)) {
                    CassettePlayerScreen(WalkmanTheme.TPS_L2,scene.state,CassetteActions(),Modifier.fillMaxSize())
                }
            }
        }
        for (label in scene.state.specs) {
            val node=compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            val child=node.getUnclippedBoundsInRoot()
            val viewport=compose.onNodeWithTag("cassette-metadata-viewport").getUnclippedBoundsInRoot()
            assertTrue("Full source information must be reachable: $label",child.left.value>=viewport.left.value-.5f)
            assertTrue("No source value may remain cropped after scrolling to it: $label",child.right.value<=viewport.right.value+.5f)
        }
    }

    @Test fun portraitFont200ReadyKeepsBottomControlsInsideTheInitialSafeViewport() {
        var mode by mutableStateOf(NowPlayingMode.CASSETTE)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f,2f)) {
                key(mode) {
                    Box(Modifier.size(400.dp,800.dp).testTag(SCENE)) {
                        val scene=Scene(false,TitleKind.LONG,true,2f,false,mode)
                        CassettePlayerScreen(WalkmanTheme.TPS_L2,scene.state,CassetteActions(),
                            Modifier.fillMaxSize().padding(top=24.dp,bottom=16.dp),mode=mode)
                    }
                }
            }
        }
        for (visual in listOf(NowPlayingMode.CASSETTE,NowPlayingMode.VINYL)) {
            compose.runOnIdle { mode=visual }
            val root=compose.onNodeWithTag(SCENE).getUnclippedBoundsInRoot()
            for(label in listOf("Phát","Bài trước","Bài tiếp","Hàng đợi","Cài đặt âm thanh","Chọn màu trình phát")) {
                val node=compose.onNodeWithContentDescription(label).assertIsDisplayed()
                val bounds=node.getUnclippedBoundsInRoot()
                assertTrue("$visual $label must fit without scroll-to",bounds.top.value>=root.top.value+24f-.5f)
                assertTrue("$visual $label must not extend below the safe viewport",bounds.bottom.value<=root.bottom.value-16f+.5f)
            }
        }
    }

    @Test fun missingMetadataDoesNotLeaveAnEmptyBandBetweenArtworkAndSeek() {
        var scene by mutableStateOf(Scene(false,TitleKind.RAW_FILE,false,1f,true,NowPlayingMode.CASSETTE))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f,scene.fontScale)) {
                key(scene.id) {
                    Box(Modifier.size(400.dp,800.dp).testTag(SCENE)) {
                        CassettePlayerScreen(WalkmanTheme.TPS_L2,scene.state,CassetteActions(),Modifier.fillMaxSize(),mode=scene.mode)
                    }
                }
            }
        }
        for (visual in listOf(NowPlayingMode.CASSETTE,NowPlayingMode.VINYL)) for(scale in listOf(1f,2f)) {
            compose.runOnIdle { scene=scene.copy(mode=visual,fontScale=scale) }
            val art=compose.onNodeWithTag("cassette-art").getUnclippedBoundsInRoot()
            val seek=compose.onNodeWithTag("cassette-progress").getUnclippedBoundsInRoot()
            assertTrue("$visual scale$scale: no spacer may separate the artwork from seek",seek.top.value-art.bottom.value<=24f)
        }
    }

    private fun assertProgress(tag:String,expected:Float) {
        val value=compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(expected,value.current,0f)
    }

    private fun assertLandscapeContentsFit(case:Scene,labelTag:String) {
        val root=compose.onNodeWithTag(SCENE).getUnclippedBoundsInRoot()
        fun assertInside(node:SemanticsNodeInteraction) {
            node.assertIsDisplayed()
            val bounds=node.getUnclippedBoundsInRoot()
            assertTrue("${case.id}: child left ${bounds.left} < viewport ${root.left}",bounds.left.value>=root.left.value-.5f)
            assertTrue("${case.id}: child right ${bounds.right} > viewport ${root.right}",bounds.right.value<=root.right.value+.5f)
            assertTrue("${case.id}: child top ${bounds.top} outside safe area",bounds.top.value>=root.top.value+24f-.5f)
            assertTrue("${case.id}: child bottom ${bounds.bottom} outside safe area",bounds.bottom.value<=root.bottom.value-16f+.5f)
        }
        for (label in listOf("Thu nhỏ trình phát","Chọn màu trình phát","Thêm vào yêu thích","Hàng đợi",
            "Cài đặt âm thanh","Ngẫu nhiên: tắt","Lặp một bài: tắt","Bài trước","Phát","Bài tiếp")) {
            assertInside(compose.onNodeWithContentDescription(label))
        }
        for (tag in listOf("cassette-art","cassette-metadata","cassette-progress","cassette-time-position","cassette-time-duration"))
            assertInside(compose.onNodeWithTag(tag))
        assertInside(compose.onNodeWithTag(labelTag,useUnmergedTree=true))
        val art=compose.onNodeWithTag("cassette-art").getUnclippedBoundsInRoot()
        assertTrue("${case.id}: artwork should fill at least 68% of safe landscape height",(art.bottom.value - art.top.value) >= (case.height-40)*.68f)
    }
    private fun diversity(bitmap:Bitmap):Int {
        val colors=mutableSetOf<Int>()
        for(y in 12 until bitmap.height-12 step 6)for(x in 12 until bitmap.width-12 step 6)colors+=bitmap.getPixel(x,y)
        return colors.size
    }
    private fun ByteArray.hex()=joinToString("") { "%02x".format(it) }
    private enum class TitleKind { SHORT,LONG,RAW_FILE }
    private data class Scene(val landscape:Boolean,val titleKind:TitleKind,val dsd:Boolean,val fontScale:Float,val idle:Boolean,val mode:NowPlayingMode) {
        val width get()=if(landscape)900 else 400
        val height get()=if(landscape)360 else 800
        val id get()="${mode.id}-${if(landscape)"landscape360" else "portrait"}-${titleKind.name.lowercase()}-${if(dsd)"dsd" else "pcm"}-font${if(fontScale==2f)200 else 100}-${if(idle)"idle-unknown" else "ready"}"
        val state:CassetteScreenState get() {
            val display=when(titleKind) {
                TitleKind.SHORT -> NowPlayingText.display(tagTitle="Mưa chiều",tagArtist="Trần Phương",tagAlbum="Những miền ký ức")
                TitleKind.LONG -> NowPlayingText.display(tagTitle=LONG_TITLE,tagArtist="Trần Phương www.example.com",tagAlbum="Những miền ký ức https://example.net")
                TitleKind.RAW_FILE -> NowPlayingText.display(fileName="Giấc_Mơ_Trưa_[Official Audio].${if(dsd)"dsf" else "flac"}",mediaArtist="www.example.com",mediaAlbum="https://example.net")
            }
            return CassetteScreenState(title=display.title,artist=display.artist,album=display.album,
                positionMs=if(idle)0 else 60_000,durationMs=if(idle)0 else 240_000,
                playing=false,requested=false,queueIndex=1,queueCount=35,seekEnabled=!idle,timelineKnown=!idle,
                specs=if(idle)emptyList() else if(dsd)listOf("DSD128 → PCM 88,2 kHz · float","Ngõ ra · 88,2 kHz · float")
                    else listOf("FLAC","96 kHz","24 bit","Ngõ ra · 48 kHz · 16 bit","EQ"))
        }
    }
    companion object {
        private const val SCENE="stage9b-screenshot-scene"
        private const val LONG_TITLE="Những ngày nắng rất khẽ trên miền ký ức — Đừng quên lời hẹn dưới cơn mưa cuối hạ của chúng mình giữa những mùa thương nhớ"
    }
}
