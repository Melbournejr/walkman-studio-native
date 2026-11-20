// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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

/** Native Skia component renders on a JVM host; not a physical Xiaomi screenshot or FPS test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], application=Application::class, qualifiers="w1000dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CassetteStage9RenderTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var hostView: View
    private var priorScale = 1f

    @Before fun useStaticFrameworkMotionFixture() {
        priorScale = ReflectionHelpers.getStaticField(ValueAnimator::class.java, "sDurationScale")
        ReflectionHelpers.setStaticField(ValueAnimator::class.java, "sDurationScale", 0f)
        assertFalse(ValueAnimator.areAnimatorsEnabled())
    }

    @After fun restoreFrameworkMotionScale() {
        ReflectionHelpers.setStaticField(ValueAnimator::class.java, "sDurationScale", priorScale)
    }

    @Test fun rendersPortraitLandscapeShortLongVietnameseAndDsdPcmIncludingFont200() {
        val cases = buildList {
            for (landscape in listOf(false, true)) for (longTitle in listOf(false, true)) for (dsd in listOf(false, true)) {
                add(Scene(landscape, longTitle, dsd))
            }
            add(Scene(landscape=false, longTitle=true, dsd=true, fontScale=2f))
            add(Scene(landscape=true, longTitle=true, dsd=false, fontScale=2f))
        }
        var scene by mutableStateOf(cases.first())
        compose.setContent {
            val view = LocalView.current
            SideEffect { hostView = view }
            CompositionLocalProvider(LocalDensity provides Density(1f, scene.fontScale)) {
                MaterialTheme(colorScheme=WalkmanTheme.TPS_L2.colors) {
                    Box(Modifier.fillMaxSize()) {
                        key(scene.id) {
                            Box(Modifier.size(scene.width.dp, scene.height.dp).testTag(SCENE)) {
                                CassettePlayerScreen(WalkmanTheme.TPS_L2, scene.state, CassetteActions(), Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
        val output=File("build/reports/stage9-render").apply { mkdirs() }
        val hashes=mutableSetOf<String>()
        val manifest=JSONArray()
        for (case in cases) {
            compose.runOnIdle { scene=case }
            val image=capture()
            assertEquals(case.width,image.width)
            assertEquals(case.height,image.height)
            assertTrue("Scene must contain actual vector/text pixels: ${case.id}", diversity(image)>24)
            val hash=fingerprint(image)
            assertTrue("Every matrix case must render a distinct actual scene: ${case.id}",hashes.add(hash))
            assertTrue("Full Vietnamese title must remain available to accessibility",compose.onAllNodesWithText(case.state.title, useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithText("001/035").assertExists()
            val file=File(output,"${case.id}.png")
            file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
            manifest.put(JSONObject().apply {
                put("file",file.name);put("width",image.width);put("height",image.height)
                put("orientation",if(case.landscape)"landscape" else "portrait")
                put("title",if(case.longTitle)"long Vietnamese with diacritics" else "short Vietnamese")
                put("sourceFixture",if(case.dsd)"DSD128 decoded float PCM88.2k" else "FLAC PCM24/96k")
                put("fontScale",case.fontScale.toDouble());put("pixelSha256",hash)
                put("pngSha256",MessageDigest.getInstance("SHA-256").digest(file.readBytes()).hex())
            })
        }
        assertEquals(10,hashes.size)
        File(output,"screenshots.json").writeText(JSONObject().apply {
            put("renderer","actual Compose View.draw into bitmap / native Skia / Robolectric API35")
            put("physicalDevice",false);put("fullScreenComponent",true)
            put("fixtureMetadata",true);put("motion","system reduced-motion setting; no live rotation")
            put("cases",manifest)
        }.toString(2)+"\n")
    }

    @Test fun landscapeThumbControlsAndTalkBackSeekInvokeTheirRealCallbacks() {
        var plays=0;var previous=0;var next=0;var favorites=0;var queue=0;var sound=0;var shuffle=0;var repeat=0
        var sought=-1L
        val scene=Scene(landscape=true,longTitle=true,dsd=true)
        val actions=CassetteActions(toggle={plays++},previous={previous++},next={next++},favorite={favorites++},
            queue={queue++},sound={sound++},shuffle={shuffle++},repeat={repeat++},seek={sought=it})
        compose.setContent {
            val view=LocalView.current
            SideEffect { hostView=view }
            MaterialTheme(colorScheme=WalkmanTheme.TPS_L2.colors) {
                Box(Modifier.size(scene.width.dp,scene.height.dp).testTag(SCENE)) {
                    CassettePlayerScreen(WalkmanTheme.TPS_L2,scene.state,actions,Modifier.fillMaxSize())
                }
            }
        }
        for (label in listOf("Bài trước","Phát","Bài tiếp","Hàng đợi","Cài đặt âm thanh","Ngẫu nhiên: tắt","Lặp một bài: tắt")) {
            compose.onNodeWithContentDescription(label).reach().assertIsDisplayed().assertHasClickAction().performClick()
        }
        compose.onAllNodesWithContentDescription("Thêm vào yêu thích").assertCountEquals(1)
        compose.onNodeWithContentDescription("Thêm vào yêu thích").reach().performClick()
        compose.runOnIdle {
            assertEquals(1,plays);assertEquals(1,previous);assertEquals(1,next);assertEquals(1,favorites)
            assertEquals(1,queue);assertEquals(1,sound);assertEquals(1,shuffle);assertEquals(1,repeat)
        }
        assertMinimumTouch("Phát",64f)
        assertMinimumTouch("Bài trước",56f)
        assertMinimumTouch("Bài tiếp",56f)
        val previousBounds=compose.onNodeWithContentDescription("Bài trước").reach().fetchSemanticsNode().boundsInRoot
        val controlsImage=capture()
        val ink=WalkmanTheme.TPS_L2.ink.toArgb()
        var iconHasThemeInk=false
        for(y in previousBounds.top.toInt().coerceAtLeast(0) until previousBounds.bottom.toInt().coerceAtMost(controlsImage.height))
            for(x in previousBounds.left.toInt().coerceAtLeast(0) until previousBounds.right.toInt().coerceAtMost(controlsImage.width))
                if(controlsImage.getPixel(x,y)==ink) iconHasThemeInk=true
        assertTrue("Cassette controls must use the theme ink even inside a dark-ink host",iconHasThemeInk)
        compose.onNodeWithContentDescription("Tua bài hát").reach()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(.75f) }
        compose.runOnIdle { assertEquals(180_000L,sought);sought=-1L }
        compose.onNodeWithTag("cassette-art").reach().performTouchInput { swipeLeft() }
        compose.runOnIdle { assertTrue("Swipe must seek backward within source duration",sought in 0L..59_999L) }
    }

    @Test fun font200KeepsTransportAndSeekReachableInBothOrientations() {
        var scene by mutableStateOf(Scene(landscape=false,longTitle=true,dsd=true,fontScale=2f))
        var toggles=0;var sought=-1L
        compose.setContent {
            val view=LocalView.current
            SideEffect { hostView=view }
            CompositionLocalProvider(LocalDensity provides Density(1f,scene.fontScale)) {
                MaterialTheme(colorScheme=WalkmanTheme.TPS_L2.colors) {
                    key(scene.id) {
                        Box(Modifier.size(scene.width.dp,scene.height.dp).testTag(SCENE)) {
                            CassettePlayerScreen(WalkmanTheme.TPS_L2,scene.state,CassetteActions(toggle={toggles++},seek={sought=it}),Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        for (landscape in listOf(false,true)) {
            compose.runOnIdle { scene=Scene(landscape=landscape,longTitle=true,dsd=true,fontScale=2f) }
            compose.onNodeWithContentDescription("Phát").reach().assertIsDisplayed().performClick()
            assertMinimumTouch("Phát",64f)
            compose.onNodeWithContentDescription("Tua bài hát").reach().assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(.5f) }
            compose.runOnIdle { assertEquals(120_000L,sought);sought=-1L }
        }
        compose.runOnIdle { assertEquals(2,toggles) }
    }

    // GĐ9b landscape intentionally fits without a vertical scroll container. Requiring
    // immediate visibility there is stronger than the old scroll-to reachability check.
    private fun SemanticsNodeInteraction.reach():SemanticsNodeInteraction {
        val viewport=compose.onNodeWithTag("cassette-player").fetchSemanticsNode().boundsInRoot
        return if(viewport.width>viewport.height) assertIsDisplayed() else performScrollTo().assertIsDisplayed()
    }

    private fun assertMinimumTouch(label:String,minPixels:Float) {
        val node=compose.onNodeWithContentDescription(label).reach().assertIsDisplayed().fetchSemanticsNode()
        assertTrue("$label width must be >=${minPixels}dp at mdpi",node.boundsInRoot.width>=minPixels)
        assertTrue("$label height must be >=${minPixels}dp at mdpi",node.boundsInRoot.height>=minPixels)
    }
    private fun capture():Bitmap {
        compose.waitForIdle()
        return HostViewCapture.capture(compose,SCENE,hostView)
    }
    private fun diversity(bitmap:Bitmap):Int {
        val colors=mutableSetOf<Int>()
        for(y in 12 until bitmap.height-12 step 5) for(x in 12 until bitmap.width-12 step 5) colors+=bitmap.getPixel(x,y)
        return colors.size
    }
    private fun fingerprint(bitmap:Bitmap):String {
        val digest=MessageDigest.getInstance("SHA-256")
        val row=IntArray(bitmap.width);val bytes=ByteArray(bitmap.width*4)
        for(y in 0 until bitmap.height) {
            bitmap.getPixels(row,0,bitmap.width,0,y,bitmap.width,1)
            row.forEachIndexed { x,value -> repeat(4) { byte -> bytes[x*4+byte]=(value ushr (byte*8)).toByte() } }
            digest.update(bytes)
        }
        return digest.digest().hex()
    }
    private fun ByteArray.hex()=joinToString("") { "%02x".format(it) }

    private data class Scene(val landscape:Boolean,val longTitle:Boolean,val dsd:Boolean,val fontScale:Float=1f) {
        val width:Int get()=if(landscape)900 else 400
        val height:Int get()=if(landscape)420 else 800
        val id:String get()="${if(landscape)"landscape" else "portrait"}-${if(longTitle)"long" else "short"}-${if(dsd)"dsd" else "pcm"}${if(fontScale==2f)"-font200" else ""}"
        val state:CassetteScreenState get()=CassetteScreenState(
            title=if(longTitle)LONG_TITLE else "Mưa chiều",artist="Trần Phương",album="Những miền ký ức",
            positionMs=60_000,durationMs=240_000,playing=false,requested=false,queueIndex=1,queueCount=35,
            specs=if(dsd)listOf("DSD128 → PCM 88,2 kHz · float","Ngõ ra · 88,2 kHz · float")
                else listOf("FLAC","96 kHz","24 bit","2.304 kb/s","Ngõ ra · 48 kHz · 16 bit")
        )
    }
    companion object {
        private const val SCENE="stage9-render-scene"
        private const val LONG_TITLE="Những ngày nắng rất khẽ trên miền ký ức — Đừng quên lời hẹn dưới cơn mưa cuối hạ của chúng mình"
    }
}
