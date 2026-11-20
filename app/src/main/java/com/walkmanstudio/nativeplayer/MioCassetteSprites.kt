// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.compose.ui.platform.testTag
import androidx.annotation.DrawableRes
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Bounds come from the owner-supplied manifest and share its 941 × 1672 canvas.
private data class CassetteRect(val x: Int, val y: Int, val width: Int, val height: Int)
private data class CassetteSprite(@param:DrawableRes val image: Int, val bounds: CassetteRect)

private val housing = CassetteSprite(R.drawable.mio_cassette_housing_clean_plate, CassetteRect(0, 0, 941, 1672))
private val tapeBed = CassetteSprite(R.drawable.mio_cassette_tape_window_background, CassetteRect(82, 484, 779, 253))
private val leftReel = CassetteSprite(R.drawable.mio_cassette_reel_left, CassetteRect(140, 493, 234, 234))
private val rightReel = CassetteSprite(R.drawable.mio_cassette_reel_right, CassetteRect(559, 493, 234, 234))
private val label = CassetteSprite(R.drawable.mio_cassette_label_blank, CassetteRect(75, 371, 781, 111))
private val frame = CassetteSprite(R.drawable.mio_cassette_window_frame, CassetteRect(52, 323, 843, 553))
private val previousKey = CassetteSprite(R.drawable.mio_cassette_key_previous, CassetteRect(78, 1229, 181, 165))
private val stopKey = CassetteSprite(R.drawable.mio_cassette_key_stop, CassetteRect(272, 1229, 183, 165))
private val playKey = CassetteSprite(R.drawable.mio_cassette_key_pause, CassetteRect(466, 1229, 194, 165))
private val nextKey = CassetteSprite(R.drawable.mio_cassette_key_next, CassetteRect(672, 1229, 181, 165))

private fun CassetteRect.place(unit: Dp) = Modifier.offset(unit * x, unit * y).size(unit * width, unit * height)

@Composable
private fun Sprite(sprite: CassetteSprite, modifier: Modifier) {
    Image(painterResource(sprite.image), contentDescription = null, modifier = modifier, contentScale = ContentScale.FillBounds)
}

@Composable
internal fun MioCassetteSprites(
    state: CassetteScreenState, actions: CassetteActions, rotation: State<Double>,
    progress: Float, tapeSeek: Modifier, seek: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val labelInk = Color(0xFF35271B)
    val title = state.title.ifBlank { "Chưa chọn bài" }
    BoxWithConstraints(Modifier.fillMaxSize().mioGraphite().clipToBounds()) {
        // Keep the approved 390 dp portrait scale on large phones. On short
        // windows, scroll before shrinking the hardware below a usable width.
        val canvasWidth = minOf(maxWidth * 1.018f, 390.dp * 1.018f,
            (maxHeight * (941f / 1672f)).coerceAtLeast(320.dp))
        val unit = canvasWidth / 941
        val canvasHeight = unit * 1672
        val top = ((maxHeight - canvasHeight).coerceAtLeast(0.dp) * .42f).coerceAtMost(40.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = top),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.requiredWidth(canvasWidth).height(canvasHeight)) {
                Sprite(housing, housing.bounds.place(unit))
                Spacer(CassetteRect(20, 154, 900, 1449).place(unit).testTag("mio-case"))
                // The forged tape texture stays still while both exposed hubs turn.
                Sprite(tapeBed, tapeBed.bounds.place(unit))
                Canvas(tapeBed.bounds.place(unit).clipToBounds()) {
                    drawRect(Brush.verticalGradient(listOf(Color(0xE60A0909), Color(0xA6160D0B), Color(0xDF090909))))
                    for ((index, x) in listOf(.225f, .763f).withIndex()) {
                        val center = Offset(size.width * x, size.height * .50f)
                        val radius = size.height * if (index == 0) .73f else .76f
                        drawCircle(Brush.radialGradient(0f to Color(0xFF12100E),
                            .65f to Color(0xFF4F2717), .90f to Color(0xFF2B1B15), 1f to Color.Transparent,
                            center = center, radius = radius), radius, center)
                        repeat(14) { winding ->
                            drawCircle(Color(0xFFAD7850).copy(alpha = if (winding % 3 == 0) .24f else .10f),
                                radius * (.55f + winding * .030f), center, style = Stroke(.48.dp.toPx()))
                        }
                    }
                    drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .34f), Color.Transparent,
                        Color.Transparent, Color.Black.copy(alpha = .38f))))
                }
                Sprite(leftReel, leftReel.bounds.place(unit).graphicsLayer {
                    transformOrigin = TransformOrigin(.5f, .5f)
                    rotationZ = (-rotation.value * .92 % 360).toFloat()
                })
                Sprite(rightReel, rightReel.bounds.place(unit).graphicsLayer {
                    transformOrigin = TransformOrigin(.5f, .5f)
                    rotationZ = (-rotation.value * 1.14 % 360).toFloat()
                })
                Canvas(CassetteRect(89, 484, 768, 260).place(unit)) {
                    val rim = CornerRadius(5.dp.toPx())
                    drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .10f), Color.Transparent,
                        Color.White.copy(alpha = .035f)), endY = size.height), cornerRadius = rim)
                    drawRoundRect(Color.White.copy(alpha = .19f), cornerRadius = rim, style = Stroke(.65.dp.toPx()))
                }
                Sprite(label, label.bounds.place(unit).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)))
                Sprite(frame, frame.bounds.place(unit).testTag("mio-window"))
                Box(CassetteRect(76, 374, 779, 105).place(unit)
                    .background(Brush.horizontalGradient(listOf(Color(0x18F5E9CF), Color(0x58FFF8E1), Color(0x00FFF8E1))))) {
                    Canvas(Modifier.fillMaxHeight().width(unit * 86)) {
                        val bands = listOf(Color(0xFFBA553B), Color(0xFFC87148), Color(0xFFBB8B4A))
                        for ((i, ink) in bands.withIndex()) {
                            val start = size.width * (.12f + .25f * i)
                            val stripe = size.width * .16f
                            drawPath(Path().apply {
                                moveTo(start + size.width * .20f, 0f)
                                lineTo(start + size.width * .20f + stripe, 0f)
                                lineTo(start + stripe, size.height)
                                lineTo(start, size.height)
                                close()
                            }, ink)
                        }
                    }
                    Row(Modifier.fillMaxSize().padding(start = unit * 108, end = unit * 32),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BasicText(title, Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge.copy(color = labelInk, fontFamily = FontFamily.Cursive, fontStyle = FontStyle.Normal, fontWeight = FontWeight.Normal),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            autoSize = TextAutoSize.StepBased(11.sp, 28.sp, 1.sp))
                        Text("C-60", style = MaterialTheme.typography.titleMedium, color = labelInk, maxLines = 1)
                    }
                }
                Box(CassetteRect(82, 487, 779, 268).place(unit).then(tapeSeek))
                Row(CassetteRect(137, 777, 673, 55).place(unit), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("TYPE I / NORMAL", color = colors.primary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    Text("MIO STUDIO", color = colors.primary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }

                val latched = CassetteKeyModel.latched(state.requested, state.playbackState)
                SpriteKey(previousKey, unit, "Bài trước", actions.previous)
                SpriteKey(stopKey, unit, "Dừng phát", actions.stop)
                SpriteKey(playKey, unit, if (latched) "Tạm dừng" else "Phát", actions.toggle,
                    if (latched) R.drawable.ic_pause else R.drawable.ic_play)
                SpriteKey(nextKey, unit, "Bài tiếp", actions.next)

                Row(CassetteRect(115, 975, 711, 100).place(unit), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        BasicText(title, style = MaterialTheme.typography.titleMedium.copy(color = colors.onSurface),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            autoSize = TextAutoSize.StepBased(10.sp, 18.sp, 1.sp))
                        BasicText(state.artist, style = MaterialTheme.typography.bodySmall.copy(color = colors.onSurfaceVariant),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            autoSize = TextAutoSize.StepBased(8.sp, 13.sp, 1.sp))
                    }
                    Canvas(Modifier.width(unit * 270).height(unit * 62)) {
                        val bars = 26
                        val step = size.width / bars
                        repeat(bars) { index ->
                            val height = size.height * (.21f + .63f * index / (bars - 1) + .09f * kotlin.math.sin(index * .8f))
                            drawRect(colors.primary.copy(alpha = if ((index + 1f) / bars <= progress) .95f else .18f),
                                Offset(index * step, size.height - height), Size(step * .52f, height))
                        }
                    }
                }
                Spacer(CassetteRect(64, 1229, 800, 165).place(unit).testTag("mio-key-row"))
                Box(Modifier.offset(unit * 110, unit * 1042).width(unit * 724)) { seek() }

                Row(Modifier.offset(unit * 78, unit * 1440).width(unit * 785), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = actions.queue) { StudioIcon(R.drawable.ic_queue, "Hàng đợi") }
                    IconButton(onClick = actions.sound) { StudioIcon(R.drawable.ic_audio_controls, "Cài đặt âm thanh") }
                    IconToggleButton(state.favorite, { actions.favorite() }) {
                        StudioIcon(if (state.favorite) R.drawable.ic_favorite else R.drawable.ic_mio_heart, "Yêu thích bài hát")
                    }
                    IconToggleButton(state.shuffled, { actions.shuffle() }) { StudioIcon(R.drawable.ic_shuffle, "Ngẫu nhiên") }
                    IconToggleButton(state.repeatOne, { actions.repeat() }) { StudioIcon(R.drawable.ic_repeat, "Lặp một bài") }
                }
                Row(Modifier.offset(y = unit * 28).fillMaxWidth().padding(horizontal = 17.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = actions.close) { StudioIcon(R.drawable.ic_expand, "Trở lại") }
                    TextButton(onClick = actions.palette, modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(22.dp), colors = ButtonDefaults.textButtonColors(
                            containerColor = Color(0xFF1C1C1D), contentColor = colors.onSurface)) {
                        Text("Retro Mode")
                        Spacer(Modifier.width(10.dp))
                        StudioIcon(R.drawable.ic_expand, modifier = Modifier.size(15.dp))
                    }
                    IconButton(onClick = actions.palette) { StudioIcon(R.drawable.ic_more, "Tùy chọn Retro") }
                }
            }
        }
    }
}

@Composable
private fun SpriteKey(sprite: CassetteSprite, unit: Dp, description: String, action: (() -> Unit)?, @DrawableRes icon: Int? = null) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val view = LocalView.current
    val bounds = sprite.bounds
    val width = maxOf(48.dp, unit * bounds.width)
    val height = maxOf(48.dp, unit * bounds.height)
    Box(Modifier.offset(unit * (bounds.x + bounds.width / 2f) - width / 2,
        unit * (bounds.y + bounds.height / 2f) - height / 2).size(width, height)
        .clickable(interaction, indication = null, enabled = action != null, role = Role.Button) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            action?.invoke()
        }
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(unit * bounds.width, unit * bounds.height).graphicsLayer {
            translationY = if (pressed) 1.5.dp.toPx() else 0f
            alpha = if (pressed) .78f else 1f
        }, contentAlignment = Alignment.Center) {
            Sprite(sprite, Modifier.matchParentSize())
            if (icon != null) Icon(painterResource(icon), null, Modifier.size(27.dp), tint = Color(0xFF35271B))
        }
    }
}
