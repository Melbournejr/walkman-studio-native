// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

enum class NowPlayingMode(val id: String, val label: String) {
    CLASSIC("classic", "Cổ điển"), CASSETTE("cassette", "Cassette"), VINYL("vinyl", "Đĩa than");
    companion object { fun find(id: String?) = entries.firstOrNull { it.id == id } ?: CLASSIC }
}

/** Geometry in normalized units; tape area (radius squared) is conserved. */
object TransportGeometry {
    const val VINYL_DEGREES_PER_SECOND = 200f // 33 1/3 rpm × 360 / 60
    fun progress(positionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) 0f else (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()
    fun tapeRadii(progress: Float): Pair<Float, Float> {
        val p = progress.coerceIn(0f, 1f)
        val hubSquared = 0.32f * 0.32f
        val tapeArea = 0.9f * 0.9f - hubSquared
        return sqrt(hubSquared + tapeArea * (1f - p)) to sqrt(hubSquared + tapeArea * p)
    }
    /** Visible tape packs keep a realistic margin around the hub and window.
     * Squared radius remains conserved; no duration means two balanced packs. */
    fun displayTapeRadii(progress: Float, durationKnown: Boolean): Pair<Float, Float> {
        val p = if (durationKnown && progress.isFinite()) progress.coerceIn(0f, 1f) else .5f
        val minimumSquared = .43f * .43f
        val tapeArea = .86f * .86f - minimumSquared
        return sqrt(minimumSquared + tapeArea * (1f - p)) to sqrt(minimumSquared + tapeArea * p)
    }
    /** Needle contact stays in the physical groove band, moving inward with progress. */
    fun stylusPoint(progress: Float, center: Offset, diameter: Float): Offset {
        require(diameter.isFinite() && diameter > 0f)
        val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        val radius = diameter * (.45f - .235f * p)
        val angle = -PI / 4
        return center + Offset(cos(angle).toFloat() * radius, sin(angle).toFloat() * radius)
    }
    fun tonearmAngle(progress: Float): Float = -20f + 32f * progress.coerceIn(0f, 1f)
    fun animationAllowed(playing: Boolean, seeking: Boolean, started: Boolean, interactive: Boolean, unlocked: Boolean, animationsEnabled: Boolean) =
        playing && !seeking && started && interactive && unlocked && animationsEnabled
    fun draggedProgress(start: Float, delta: Float, width: Float): Float =
        if (width <= 0f) start.coerceIn(0f, 1f) else (start + delta / width).coerceIn(0f, 1f)
}

/** Screen broadcasts cover OEMs that leave an activity STARTED while the display is off.
 * Animation scale is observed so changing the system's reduced-motion setting takes effect immediately.
 */
@Composable
internal fun transportAnimationAllowed(playing: Boolean, seeking: Boolean): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    val keyguard = remember(context) { context.getSystemService(KeyguardManager::class.java) }
    var started by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var interactive by remember(context) { mutableStateOf(power.isInteractive) }
    var unlocked by remember(context) { mutableStateOf(!keyguard.isKeyguardLocked) }
    var enabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(context, lifecycle) {
        fun refresh() {
            interactive = power.isInteractive
            unlocked = !keyguard.isKeyguardLocked
            enabled = ValueAnimator.areAnimatorsEnabled()
        }
        val observer = LifecycleEventObserver { _, _ -> started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED); refresh() }
        val screen = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { refresh() } }
        val scale = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) { refresh() } }
        lifecycle.addObserver(observer)
        ContextCompat.registerReceiver(context, screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, scale)
        refresh()
        onDispose { lifecycle.removeObserver(observer); context.unregisterReceiver(screen); context.contentResolver.unregisterContentObserver(scale) }
    }
    return TransportGeometry.animationAllowed(playing, seeking, started, interactive, unlocked, enabled)
}

/** No frame is requested when inactive. A new run starts with a fresh timestamp (no background catch-up). */
@Composable
private fun transportRotation(active: Boolean): State<Double> {
    val angle = remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                angle.doubleValue += (now - previous) / 1_000_000_000.0 * TransportGeometry.VINYL_DEGREES_PER_SECOND
                previous = now
            }
        }
    }
    return angle
}

/** The only drag outcome is a single seek on release; moving the artwork never changes the playback pipeline. */
@Composable
fun NowPlayingVisual(
    mode: NowPlayingMode, theme: WalkmanTheme, title: String, artist: String,
    positionMs: Long, durationMs: Long, playing: Boolean, seeking: Boolean,
    onSeekingChange: (Boolean) -> Unit, onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier, visible: Boolean = true,
    durationKnown: Boolean = durationMs > 0, seekEnabled: Boolean = durationMs > 0,
    albumArt: @Composable (Modifier) -> Unit,
) {
    val safeDuration = durationMs.coerceAtLeast(0)
    val progress = if (durationKnown) TransportGeometry.progress(positionMs, safeDuration) else 0f
    var dragged by remember(mode, title, durationMs) { mutableStateOf<Float?>(null) }
    val currentSeeking by rememberUpdatedState(onSeekingChange)
    val currentSeek by rememberUpdatedState(onSeek)
    val currentProgress by rememberUpdatedState(progress)
    DisposableEffect(mode, title, durationMs) { onDispose { currentSeeking(false) } }
    val displayProgress = dragged ?: progress
    val active = transportAnimationAllowed(playing && visible, seeking || dragged != null)
    val rotation = transportRotation(active)
    val seekable = seekEnabled && durationKnown && safeDuration > 0
    val seekModifier = Modifier.semantics(mergeDescendants = true) {
        contentDescription = "${mode.label}. Vuốt ngang để tua bài hát"
        stateDescription = "${clockTime(if (durationKnown) (displayProgress * safeDuration).toLong() else 0)} trên ${if (safeDuration > 0) clockTime(safeDuration) else "--:--"}, ${if (playing) "đang phát" else "tạm dừng"}"
        progressBarRangeInfo = ProgressBarRangeInfo(displayProgress, 0f..1f)
        if (seekable) {
            setProgress { value -> currentSeek((value.coerceIn(0f, 1f) * durationMs).toLong()); true }
            customActions = listOf(
                CustomAccessibilityAction("Lùi 10 giây") { currentSeek((positionMs - 10_000).coerceAtLeast(0)); true },
                CustomAccessibilityAction("Tiến 10 giây") { currentSeek((positionMs + 10_000).coerceAtMost(durationMs)); true },
            )
        }
    }.pointerInput(mode, durationMs, seekable) {
        if (!seekable) return@pointerInput
        var start = 0f; var delta = 0f
        detectHorizontalDragGestures(
            onDragStart = { start = currentProgress; delta = 0f; dragged = start; currentSeeking(true) },
            onDragCancel = { dragged = null; currentSeeking(false) },
            onDragEnd = { dragged?.let { currentSeek((it * durationMs).toLong()) }; dragged = null; currentSeeking(false) },
            onHorizontalDrag = { change, amount -> change.consume(); delta += amount; dragged = TransportGeometry.draggedProgress(start, delta, size.width.toFloat()) },
        )
    }
    Box(modifier.then(seekModifier), contentAlignment = Alignment.Center) {
        when (mode) {
            NowPlayingMode.CASSETTE -> CassetteFace(theme, title, artist, displayProgress, (displayProgress * safeDuration).toLong(), rotation, durationKnown)
            NowPlayingMode.VINYL -> VinylFace(theme, title, displayProgress, !playing || seeking || dragged != null, rotation, albumArt)
            NowPlayingMode.CLASSIC -> albumArt(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun VinylFace(theme: WalkmanTheme, title: String, progress: Float, lifted: Boolean, rotation: State<Double>, albumArt: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val diameter = size.minDimension * .76f
            val center = Offset(size.width * .46f, size.height * .41f)
            drawRoundRect(theme.face, cornerRadius = CornerRadius(16.dp.toPx()))
            drawCircle(Color(0xFF080B0C), diameter / 2 + 3.dp.toPx(), center)
            // Concentric vector grooves; rotational marker and label carry visible movement.
            repeat(42) { groove -> drawCircle(Color(0xFF485052).copy(alpha = if (groove % 4 == 0) .55f else .25f), diameter * (.19f + groove * .0072f), center, style = Stroke(.65.dp.toPx())) }
            rotate((rotation.value % 360).toFloat(), center) {
                drawArc(Color.White.copy(alpha = .07f), 200f, 38f, false, center - Offset(diameter / 2, diameter / 2), Size(diameter, diameter), style = Stroke(diameter * .16f))
                drawCircle(theme.accent, 2.dp.toPx(), center + Offset(0f, -diameter * .45f))
            }
            drawCircle(theme.accent, diameter * .165f, center)
        }
        // Explicit square then circular outer clip keeps every cover/placeholder circular.
        Box(Modifier.offset(x = -maxWidth * .04f, y = -maxHeight * .09f).size(minOf(maxWidth, maxHeight) * .24f)
            .graphicsLayer { rotationZ = (rotation.value % 360).toFloat() }.clip(CircleShape)) { albumArt(Modifier.fillMaxSize()) }
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width * .46f, size.height * .41f)
            drawCircle(theme.ink, 3.dp.toPx(), center)
            val pivot = Offset(size.width * .88f, size.height * .16f)
            val diameter = size.minDimension * .76f
            val contact = TransportGeometry.stylusPoint(progress, center, diameter)
            val head = if (lifted) pivot + Offset(-diameter * .055f, diameter * .41f) else contact
            val elbow = pivot + (head - pivot) * .78f + Offset(diameter * .025f, 0f)
            val shadow = if (lifted) Offset(5.dp.toPx(), 5.dp.toPx()) else Offset(1.5.dp.toPx(), 1.5.dp.toPx())
            drawLine(Color.Black.copy(alpha = .30f), pivot + shadow, elbow + shadow, 7.dp.toPx(), StrokeCap.Round)
            drawLine(Color.Black.copy(alpha = .30f), elbow + shadow, head + shadow, 7.dp.toPx(), StrokeCap.Round)
            drawLine(theme.accent, pivot, elbow, 4.dp.toPx(), StrokeCap.Round)
            drawLine(theme.accent, elbow, head, 5.dp.toPx(), StrokeCap.Round)
            drawCircle(theme.ink, 4.dp.toPx(), head)
            if (!lifted) drawCircle(theme.accent, 1.1.dp.toPx(), contact)
            drawCircle(theme.body, size.minDimension * .055f, pivot)
            drawCircle(theme.accent, size.minDimension * .035f, pivot)
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = maxHeight * .025f)
            .width(maxWidth * .88f).height(maxHeight * .20f)
            .background(theme.display.copy(alpha = .96f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)) {
            WordFitLabel(title.ifBlank { "Chưa chọn bài" }, theme.displayInk,
                modifier = Modifier.fillMaxSize(), testTag = "vinyl-label", minFontSp = 12, maxFontSp = 20)
        }
    }
}
