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
import androidx.compose.runtime.snapshots.Snapshot
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
import androidx.media3.common.Player
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w1000dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalRoborazziApi::class)
class Stage9cScreenshotTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var hostView:View
    private var priorScale=1f
    @Before fun deterministicReducedMotion() {
        priorScale=ReflectionHelpers.getStaticField(ValueAnimator::class.java,"sDurationScale")
        ReflectionHelpers.setStaticField(ValueAnimator::class.java,"sDurationScale",0f)
        assertFalse(ValueAnimator.areAnimatorsEnabled())
    }
    @After fun restoreFrameworkMotion() {
        ReflectionHelpers.setStaticField(ValueAnimator::class.java,"sDurationScale",priorScale)
        compose.mainClock.autoAdvance=true
    }

    @Test fun records72CassetteScenesAndAssertsUnclippedDeckAndPhysicalKeyBounds() {
        val cases=buildList {
            for(landscape in listOf(false,true)) for(status in Status.entries) for(title in TitleKind.entries)
                for(dsd in listOf(false,true)) for(scale in listOf(1f,2f)) add(Scene(landscape,status,title,dsd,scale))
        }
        assertEquals(72,cases.size)
        var scene by mutableStateOf(cases.first())
        compose.setContent {
            val view=LocalView.current;SideEffect { hostView=view }
            CompositionLocalProvider(LocalDensity provides Density(1f,scene.scale)) {
                MaterialTheme(colorScheme=WalkmanTheme.TPS_L2.colors) {
                    key(scene.id) { Box(Modifier.size(scene.width.dp,scene.height.dp).background(Color.Black).testTag(SCENE)) {
                        CassettePlayerScreen(WalkmanTheme.TPS_L2,scene.state,CassetteActions(),
                            Modifier.fillMaxSize().padding(top=24.dp,bottom=16.dp))
                    } }
                }
            }
        }
        val output=File("build/reports/stage9c-screens").apply { mkdirs() }
        val manifest=JSONArray();val timings=mutableListOf<Double>()
        val options=RoborazziOptions(taskType=RoborazziTaskType.Record,captureType=RoborazziOptions.CaptureType.Screenshot())
        for(case in cases) {
            compose.runOnIdle { scene=case };compose.waitForIdle()
            assertSceneBounds(case)
            compose.onAllNodesWithText(case.state.title,useUnmergedTree=true).assertCountEquals(1)
            compose.onAllNodesWithContentDescription("Thêm vào yêu thích").assertCountEquals(1)
            assertKeyState(case.status==Status.PLAYING)
            if(case.status==Status.IDLE_UNKNOWN) {
                compose.onNodeWithTag("cassette-time-position").assertTextEquals("0:00")
                compose.onNodeWithTag("cassette-time-duration").assertTextEquals("--:--")
                compose.onNodeWithTag("cassette-progress").assertIsNotEnabled()
            } else compose.onNodeWithTag("cassette-time-duration").assertTextEquals("4:00")
            // Includes host synchronization, bitmap allocation and View.draw. Not CPU%,
            // GPU frame timing, real-time playback or phone FPS/battery measurements.
            val start=System.nanoTime();val bitmap=HostViewCapture.capture(compose,SCENE,hostView)
            val renderMs=(System.nanoTime()-start)/1_000_000.0;timings+=renderMs
            assertEquals(case.width,bitmap.width);assertEquals(case.height,bitmap.height)
            assertTrue("Real vector/text pixels must render for ${case.id}",diversity(bitmap)>24)
            val file=File(output,"${case.id}.png");bitmap.captureRoboImage(file,options);bitmap.recycle()
            assertTrue(file.isFile && file.length()>1000)
            manifest.put(JSONObject().apply {
                put("file",file.name);put("width",case.width);put("height",case.height)
                put("orientation",if(case.landscape)"landscape" else "portrait");put("mode","cassette")
                put("titleCase",case.title.name);put("displayTitle",case.state.title);put("sourceFixture",if(case.dsd)"DSD128" else "FLAC PCM24/96k")
                put("fontScale",case.scale.toDouble());put("playbackFixture",case.status.name)
                put("playKeyLatched",case.status==Status.PLAYING);put("safeInsetsDp",JSONObject().put("top",24).put("bottom",16))
                put("hostCaptureMs",renderMs);put("pngSha256",MessageDigest.getInstance("SHA-256").digest(file.readBytes()).hex())
            })
        }
        assertEquals(72,manifest.length());assertEquals(72,output.listFiles { f->f.extension=="png" }!!.size)
        File(output,"screenshots.json").writeText(JSONObject().apply {
            put("framework","Roborazzi 1.52.0 / Robolectric API35 / native Skia / mdpi host")
            put("physicalDevice",false);put("fixtureMetadata",true);put("motion","system reduced-motion; static mechanical states")
            put("cases",manifest)
        }.toString(2)+"\n")
        val sorted=timings.sorted()
        File(output,"host-render-metrics.json").writeText(JSONObject().apply {
            put("physicalDevice",false);put("host","Robolectric/native Skia JVM server")
            put("measurement","System.nanoTime around HostViewCapture.capture: synchronization + bitmap allocation + View.draw; one static capture per scene")
            put("frames",timings.size);put("meanMs",timings.average());put("p50Ms",sorted[sorted.size/2]);put("p95Ms",sorted[(sorted.size*.95).toInt().coerceAtMost(sorted.lastIndex)])
            put("notMeasured","phone CPU/GPU/FPS/battery, animated steady-state rendering, physical Xiaomi")
        }.toString(2)+"\n")
    }

    @Test fun compact360By640AtFont100FitsWithoutScrollOrCutControls() {
        var scene by mutableStateOf(Scene(false,Status.PLAYING,TitleKind.SHORT,false,1f,compact=true))
        compose.setContent {
            key(scene.id) { Box(Modifier.size(360.dp,640.dp).testTag(SCENE)) {
                CassettePlayerScreen(WalkmanTheme.TPS_L2,scene.state,CassetteActions(),Modifier.fillMaxSize().padding(top=24.dp,bottom=16.dp))
            } }
        }
        for(status in Status.entries) for(title in TitleKind.entries) for(dsd in listOf(false,true)) {
            val case=Scene(false,status,title,dsd,1f,compact=true)
            compose.runOnIdle { scene=case };compose.waitForIdle();assertSceneBounds(case)
            val scroll=compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().mapNotNull {
                it.config.getOrElseNullable(SemanticsProperties.VerticalScrollAxisRange) { null }
            }
            assertTrue("Portrait must retain an accessibility scroll ancestor",scroll.isNotEmpty())
            assertTrue("360x640 font100 must fit without vertical scrolling",scroll.all { it.maxValue()<=.5f })
        }
    }

    @Test fun externalStateChangesLatchReleaseAndReducedMotionUpdateImmediately() {
        var requested by mutableStateOf(false);var playerState by mutableIntStateOf(Player.STATE_READY)
        var toggles=0;var previous=0
        compose.setContent {
            MaterialTheme { MechanicalTransport(WalkmanTheme.TPS_L2,requested,playerState,false,
                {toggles++},{previous++},{},Modifier.width(336.dp)) }
        }
        assertKeyState(false)
        compose.runOnIdle { requested=true };assertKeyState(true)
        compose.runOnIdle { playerState=Player.STATE_ENDED };assertKeyState(false)
        compose.runOnIdle { playerState=Player.STATE_IDLE };assertKeyState(false)
        compose.runOnIdle { playerState=Player.STATE_BUFFERING };assertKeyState(true)
        compose.runOnIdle { requested=false };assertKeyState(false)
        compose.onNodeWithTag("mechanical-play").performClick();assertKeyState(false)
        compose.onNodeWithTag("mechanical-previous").performTouchInput { down(center) }
        compose.waitForIdle()
        assertEquals(1f,compose.onNodeWithTag("mechanical-previous").fetchSemanticsNode().config[MechanicalDepression],0f)
        compose.onNodeWithTag("mechanical-previous").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(0f,compose.onNodeWithTag("mechanical-previous").fetchSemanticsNode().config[MechanicalDepression],0f)
        compose.runOnIdle { assertEquals(1,toggles);assertEquals(1,previous) }
    }

    @Test fun finiteKeyAnimationSettlesAndTurningMotionOffSnapsWithoutAFrameLoop() {
        var requested by mutableStateOf(false);var enabled by mutableStateOf(true)
        var observedRequested=false
        val rawState=Player.STATE_READY
        val trace=JSONArray()
        compose.mainClock.autoAdvance=false
        compose.setContent {
            val input=requested
            SideEffect { observedRequested=input }
            MaterialTheme { MechanicalTransport(WalkmanTheme.TPS_L2,input,rawState,enabled,
                {},{},{},Modifier.width(336.dp)) }
        }
        val start=compose.mainClock.currentTime
        fun record(step:String) {
            val node=compose.onNodeWithTag("mechanical-play").fetchSemanticsNode()
            val latched=node.config[MechanicalLatched]
            trace.put(JSONObject().apply {
                put("step",step);put("testClockMs",compose.mainClock.currentTime-start)
                put("playWhenReadyInput",requested);put("composedPlayWhenReady",observedRequested)
                put("rawPlayerState",rawState);put("renderedLatched",latched)
                put("targetFromComposedInput",CassetteKeyModel.targetDepression(CassetteKeyModel.latched(observedRequested,rawState),false).toDouble())
                put("targetFromRenderedLatch",CassetteKeyModel.targetDepression(latched,false).toDouble())
                put("animationValue",node.config[MechanicalDepression].toDouble())
                put("valueAnimatorDurationScale",ReflectionHelpers.getStaticField<Float>(ValueAnimator::class.java,"sDurationScale").toDouble())
            })
        }
        try {
            record("initial")
            compose.runOnIdle { requested=true; Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeBy(160)
            record("single-advance-160ms-after-apply-notifications")
            // Same total160ms and exact assertion; publish snapshots before advancing.
            assertEquals(1f,compose.onNodeWithTag("mechanical-play").fetchSemanticsNode().config[MechanicalDepression],.01f)
            compose.runOnIdle { requested=false;enabled=false; Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeByFrame()
            record("pause-reduced-motion-one-frame")
            assertKeyState(false)
            compose.runOnIdle { requested=true; Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeByFrame()
            record("play-reduced-motion-one-frame")
            assertKeyState(true)
        } finally {
            File("build/reports/stage9c-animation-diagnostic.json").apply { parentFile!!.mkdirs() }
                .writeText(JSONObject().put("diagnostic","unchanged production; scale0; snapshot publication before one160ms advance; no time increase")
                    .put("samples",trace).toString(2)+"\n")
        }
    }

    @Test fun displayedClockAndTapeCounterKeepExactLiveMilliseconds() {
        var position by mutableLongStateOf(1000L)
        var duration by mutableLongStateOf(180001L)
        var known by mutableStateOf(true)
        compose.setContent {
            Box(Modifier.size(400.dp,800.dp)) {
                CassettePlayerScreen(WalkmanTheme.TPS_L2,CassetteScreenState(title="Mưa chiều",positionMs=position,
                    durationMs=duration,timelineKnown=known,seekEnabled=false),CassetteActions(),Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithTag("cassette-time-position").assertTextEquals("0:01")
        compose.onAllNodesWithText("0:01",useUnmergedTree=true).assertCountEquals(2)
        compose.runOnIdle { position=duration }
        compose.onNodeWithTag("cassette-time-position").assertTextEquals("3:00")
        compose.onAllNodesWithText("3:00",useUnmergedTree=true).assertCountEquals(3)
        compose.runOnIdle { position=0 }
        compose.onNodeWithTag("cassette-time-position").assertTextEquals("0:00")
        compose.onAllNodesWithText("0:00",useUnmergedTree=true).assertCountEquals(2)
        compose.runOnIdle { position=120_000;duration=0;known=false }
        compose.onNodeWithTag("cassette-time-position").assertTextEquals("0:00")
        compose.onNodeWithTag("cassette-time-duration").assertTextEquals("--:--")
        compose.onAllNodesWithText("0:00",useUnmergedTree=true).assertCountEquals(2)
        compose.runOnIdle { duration=180001 }
        compose.onNodeWithTag("cassette-time-position").assertTextEquals("0:00")
        compose.onNodeWithTag("cassette-time-duration").assertTextEquals("3:00")
    }

    @Test fun narrowLandscapeKeepsFullWidthTapeTitleControlsAndTimesInsideSafeAreaAt200Percent() {
        val cases=listOf(false,true).map { Scene(true,Status.PLAYING,TitleKind.LONG,it,2f,compact=true) }
        var scene by mutableStateOf(cases.first())
        compose.setContent {
            val view=LocalView.current;SideEffect { hostView=view }
            CompositionLocalProvider(LocalDensity provides Density(1f,scene.scale)) {
                MaterialTheme(colorScheme=WalkmanTheme.TPS_L2.colors) {
                    key(scene.id) { Box(Modifier.size(scene.width.dp,scene.height.dp).background(Color.Black).testTag(SCENE)) {
                        CassettePlayerScreen(WalkmanTheme.TPS_L2,scene.state,CassetteActions(),
                            Modifier.fillMaxSize().padding(top=24.dp,bottom=16.dp))
                    } }
                }
            }
        }
        val output=File("build/reports/stage9d-narrow-screens").apply { mkdirs() }
        for(case in cases) {
            compose.runOnIdle { scene=case };compose.waitForIdle()
            assertSceneBounds(case)
            compose.onAllNodesWithText(case.state.title,useUnmergedTree=true).assertCountEquals(1)
            val bitmap=HostViewCapture.capture(compose,SCENE,hostView)
            bitmap.captureRoboImage(File(output,"${if(case.dsd)"dsd" else "pcm"}-720x360-font200.png"),
                RoborazziOptions(taskType=RoborazziTaskType.Record,captureType=RoborazziOptions.CaptureType.Screenshot()))
            bitmap.recycle()
        }
    }

    private fun assertSceneBounds(case:Scene) {
        val root=compose.onNodeWithTag(SCENE).getUnclippedBoundsInRoot()
        fun inside(node:SemanticsNodeInteraction) {
            node.assertIsDisplayed();val b=node.getUnclippedBoundsInRoot()
            assertTrue("${case.id}: left",b.left.value>=root.left.value-.5f)
            assertTrue("${case.id}: right",b.right.value<=root.right.value+.5f)
            assertTrue("${case.id}: top safe inset",b.top.value>=root.top.value+24f-.5f)
            assertTrue("${case.id}: bottom safe inset",b.bottom.value<=root.bottom.value-16f+.5f)
        }
        for(tag in listOf("cassette-art","cassette-tape","mechanical-keys","cassette-progress","cassette-time-position","cassette-time-duration")) inside(compose.onNodeWithTag(tag))
        inside(compose.onNodeWithTag("cassette-label",useUnmergedTree=true))
        for(label in listOf("Thu nhỏ trình phát","Thêm vào yêu thích","Ngẫu nhiên: tắt","Lặp một bài: tắt","Hàng đợi","Cài đặt âm thanh","Chọn màu trình phát")) inside(compose.onNodeWithContentDescription(label))
        val deck=compose.onNodeWithTag("cassette-art").getUnclippedBoundsInRoot()
        val tape=compose.onNodeWithTag("cassette-tape").getUnclippedBoundsInRoot()
        val keys=compose.onNodeWithTag("mechanical-keys").getUnclippedBoundsInRoot()
        assertTrue("Keys belong below the tape",keys.top.value>=tape.bottom.value-.5f)
        assertTrue("Keys must be inside the deck",keys.bottom.value<=deck.bottom.value+.5f)
        for((tag,min) in listOf("mechanical-previous" to 56f,"mechanical-play" to 64f,"mechanical-next" to 56f)) {
            val node=compose.onNodeWithTag(tag);inside(node);val b=node.getUnclippedBoundsInRoot()
            assertTrue("$tag target width",b.right.value-b.left.value>=min)
            assertTrue("$tag target height",b.bottom.value-b.top.value>=min)
        }
        val tapeRatio=(tape.right.value-tape.left.value)/(tape.bottom.value-tape.top.value)
        if(case.landscape) {
            // Stage9d explicitly replaces the landscape 1.57 tape with a full-width
            // drawing and moves all information/keys below it. Portrait is unchanged.
            assertTrue("${case.id}: landscape tape fills at least94% width",tape.right.value-tape.left.value>=case.width*.94f)
            assertTrue("${case.id}: tape remains visible above the controls",tape.bottom.value-tape.top.value>=80f)
            val label=compose.onNodeWithTag("cassette-label",useUnmergedTree=true).getUnclippedBoundsInRoot()
            assertTrue("${case.id}: title belongs below the tape",label.top.value>=tape.bottom.value-.5f)
            assertTrue("Deck height must cover at least80% safe landscape",deck.bottom.value-deck.top.value>=(case.height-40)*.8f)
            assertTrue("Physical keys must remain inside the case",keys.left.value>=deck.left.value-.5f && keys.right.value<=deck.right.value+.5f)
        } else assertTrue("${case.id}: portrait cassette must keep actual ratio1.5..1.65, got$tapeRatio",tapeRatio in 1.5f..1.65f)
    }
    private fun assertKeyState(latched:Boolean) {
        val node=compose.onNodeWithTag("mechanical-play").fetchSemanticsNode()
        assertEquals(latched,node.config[MechanicalLatched]);assertEquals(if(latched)1f else 0f,node.config[MechanicalDepression],0f)
        assertEquals(if(latched)"Đang phát" else "Tạm dừng",node.config[SemanticsProperties.StateDescription])
    }
    private fun diversity(bitmap:Bitmap):Int {
        val colors=mutableSetOf<Int>()
        for(y in 12 until bitmap.height-12 step 6) for(x in 12 until bitmap.width-12 step 6) colors+=bitmap.getPixel(x,y)
        return colors.size
    }
    private fun ByteArray.hex()=joinToString("") { "%02x".format(it) }
    private enum class Status { PLAYING,PAUSED,IDLE_UNKNOWN }
    private enum class TitleKind { SHORT,LONG,RAW_FILE }
    private data class Scene(val landscape:Boolean,val status:Status,val title:TitleKind,val dsd:Boolean,val scale:Float,val compact:Boolean=false) {
        val width get()=if(landscape) { if(compact)720 else 900 } else if(compact)360 else 400
        val height get()=if(landscape)360 else if(compact)640 else 800
        val id get()="cassette-${if(landscape)"landscape360" else if(compact)"portrait640" else "portrait"}-${status.name.lowercase()}-${title.name.lowercase()}-${if(dsd)"dsd" else "pcm"}-font${if(scale==2f)200 else 100}"
        val state:CassetteScreenState get() {
            val idle=status==Status.IDLE_UNKNOWN
            val text=when(title) {
                TitleKind.SHORT->NowPlayingText.display(tagTitle="Mưa chiều",tagArtist="Trần Phương",tagAlbum="Những miền ký ức")
                TitleKind.LONG->NowPlayingText.display(tagTitle="Những ngày nắng rất khẽ trên miền ký ức — Đừng quên lời hẹn dưới cơn mưa cuối hạ của chúng mình",tagArtist="Trần Phương www.example.com",tagAlbum="Những miền ký ức https://example.net")
                TitleKind.RAW_FILE->NowPlayingText.display(fileName="Giấc_Mơ_Trưa_[Official Audio]_[320kbps].${if(dsd)"dsf" else "flac"}")
            }
            return CassetteScreenState(title=text.title,artist=text.artist,album=text.album,positionMs=if(idle)120_000 else 60_000,
                durationMs=if(idle)0 else 240_000,playing=status==Status.PLAYING,requested=status==Status.PLAYING,
                playbackState=if(idle)Player.STATE_IDLE else Player.STATE_READY,timelineKnown=!idle,seekEnabled=!idle,
                queueIndex=1,queueCount=35,specs=if(idle)emptyList() else if(dsd)listOf("DSD128 → PCM 88,2 kHz · float","Ngõ ra · 88,2 kHz · float")
                else listOf("FLAC","96 kHz","24 bit","Ngõ ra · 48 kHz · 16 bit","EQ"))
        }
    }
    companion object { private const val SCENE="stage9c-scene" }
}
