// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player

/** UI mechanics only: no playback command, audio state or timing is owned here. */
internal object CassetteKeyModel {
    const val PRESS_DURATION_MS = 100
    // A critically damped unit spring with this stiffness settles to 1% in ~142ms.
    const val RELEASE_STIFFNESS = 2200f
    const val DEPRESSION_DP = 4f

    fun latched(playWhenReady: Boolean, playbackState: Int): Boolean =
        playWhenReady && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)

    fun targetDepression(latched: Boolean, pressed: Boolean): Float = if (latched || pressed) 1f else 0f
    fun pressDurationMs(animationsEnabled: Boolean): Int = if (animationsEnabled) PRESS_DURATION_MS else 0
}

/** Exposes rendered geometry for bounds/state tests without inventing a second player state. */
internal val MechanicalDepression = SemanticsPropertyKey<Float>("MechanicalDepression")
internal var SemanticsPropertyReceiver.mechanicalDepression by MechanicalDepression
internal val MechanicalLatched = SemanticsPropertyKey<Boolean>("MechanicalLatched")
internal var SemanticsPropertyReceiver.mechanicalLatched by MechanicalLatched

/** Three physical keys belong inside the deck. Auxiliary controls are supplied elsewhere. */
@Composable
internal fun MechanicalTransport(
    theme: WalkmanTheme,
    playWhenReady: Boolean,
    playbackState: Int,
    animationsEnabled: Boolean,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playLatched = CassetteKeyModel.latched(playWhenReady, playbackState)
    Row(modifier.heightIn(min = 64.dp).testTag("mechanical-keys"),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        MechanicalKey(theme, R.drawable.ic_previous, "Bài trước", false, animationsEnabled, onPrevious,
            Modifier.weight(1f).height(64.dp).widthIn(min = 56.dp).testTag("mechanical-previous"))
        MechanicalKey(theme, if (playLatched) R.drawable.ic_pause else R.drawable.ic_play,
            if (playLatched) "Tạm dừng" else "Phát", playLatched, animationsEnabled, onToggle,
            Modifier.weight(1f).height(64.dp).widthIn(min = 64.dp).testTag("mechanical-play"), isPlay = true)
        MechanicalKey(theme, R.drawable.ic_next, "Bài tiếp", false, animationsEnabled, onNext,
            Modifier.weight(1f).height(64.dp).widthIn(min = 56.dp).testTag("mechanical-next"))
    }
}

@Composable
private fun MechanicalKey(
    theme: WalkmanTheme, icon: Int, label: String, latched: Boolean,
    animationsEnabled: Boolean, onClick: () -> Unit, modifier: Modifier,
    isPlay: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val target = CassetteKeyModel.targetDepression(latched, pressed)
    // Removing the animation branch cancels it immediately on background/reduced motion.
    // The disabled branch reads the new value synchronously, with no frame loop.
    val depression: State<Float> = if (animationsEnabled) animateFloatAsState(
        targetValue = target,
        animationSpec = if (target > 0f) tween(CassetteKeyModel.PRESS_DURATION_MS)
            else spring(dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = CassetteKeyModel.RELEASE_STIFFNESS, visibilityThreshold = .01f),
        label = "mechanical-key-depression",
    ) else rememberUpdatedState(target)
    val amount = depression.value.coerceIn(0f, 1f)
    val view = LocalView.current
    var hapticSentForPress by remember { mutableStateOf(false) }
    LaunchedEffect(interaction, view) {
        interaction.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    hapticSentForPress = true
                }
                is PressInteraction.Cancel -> hapticSentForPress = false
            }
        }
    }
    Box(modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button) {
        // Accessibility/keyboard activation may have no pointer PressInteraction.
        if (!hapticSentForPress) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        hapticSentForPress = false
        onClick()
    }.semantics {
        contentDescription = label
        stateDescription = if (isPlay) {
            if (latched) "Đang phát" else "Tạm dừng"
        } else if (pressed) "Đã nhấn" else "Sẵn sàng"
        mechanicalDepression = amount
        mechanicalLatched = latched
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 3.dp.toPx()
            val corner = CornerRadius(3.dp.toPx())
            val faceTop = 2.dp.toPx() + amount * CassetteKeyModel.DEPRESSION_DP.dp.toPx()
            val faceWidth = size.width - 2 * inset - 2.dp.toPx()
            val faceHeight = size.height - 13.dp.toPx()
            val thickness = (6f - amount * 4f).dp.toPx()
            val face = Size(faceWidth, faceHeight)
            val origin = Offset(inset, faceTop)
            val shade = if (theme.dark) Color.Black else Color(0xFF434C55)
            // All displaced shadows point down/right from the same top-left light.
            for (layer in 4 downTo 1) {
                val reach = (1.2f + (1f - amount) * .7f) * layer
                drawRoundRect(shade.copy(alpha = .035f),
                    origin + Offset(reach.dp.toPx(), thickness + reach.dp.toPx()), face, corner)
            }
            drawRoundRect(Brush.verticalGradient(listOf(
                lerp(theme.face, Color.Black, .42f), lerp(theme.face, Color.Black, .68f))),
                origin + Offset(0f, thickness), face, corner)
            val surface = lerp(theme.face, Color.Black, amount * .13f)
            drawRoundRect(Brush.linearGradient(listOf(
                lerp(surface, Color.White, .15f - amount * .06f), surface,
                lerp(surface, Color.Black, .12f)), origin, origin + Offset(face.width, face.height)),
                origin, face, corner)
            val highlight = Color.White.copy(alpha = .48f - amount * .12f)
            drawLine(highlight, origin + Offset(3.dp.toPx(), 1.dp.toPx()),
                origin + Offset(face.width - 3.dp.toPx(), 1.dp.toPx()), .8.dp.toPx(), StrokeCap.Round)
            drawLine(Color.White.copy(alpha = .24f), origin + Offset(1.dp.toPx(), 3.dp.toPx()),
                origin + Offset(1.dp.toPx(), face.height - 3.dp.toPx()), .8.dp.toPx(), StrokeCap.Round)
            drawLine(Color.Black.copy(alpha = .35f), origin + Offset(2.dp.toPx(), face.height),
                origin + Offset(face.width - 1.dp.toPx(), face.height), 1.dp.toPx(), StrokeCap.Round)
            if (isPlay) drawRoundRect(theme.accent.copy(alpha = if (latched) .95f else .48f),
                origin + Offset(face.width * .40f, face.height - 6.dp.toPx()),
                Size(face.width * .20f, 1.5.dp.toPx()), CornerRadius(.7.dp.toPx()))
        }
        Box(Modifier.fillMaxSize().padding(bottom = 9.dp).graphicsLayer {
            translationY = amount * CassetteKeyModel.DEPRESSION_DP.dp.toPx()
        }, contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null,
                modifier = Modifier.size(if (isPlay) 30.dp else 26.dp), tint = theme.ink)
        }
    }
}
