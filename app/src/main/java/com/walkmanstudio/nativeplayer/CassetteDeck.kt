// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Canvas positive rotation is clockwise; both tape hubs deliberately use a negative angle. */
internal object CassetteMechanics {
    const val HUB_TEETH = 6
    fun counterClockwiseAngle(rotationDegrees: Double, packRadius: Float): Float {
        require(rotationDegrees.isFinite() && packRadius.isFinite() && packRadius > 0f)
        return (-(rotationDegrees / packRadius) % 360.0).toFloat()
    }
}

/** Original deck case. All light comes from the upper left; shadows fall lower right.
 * Layered translucent vector strokes approximate soft shadows without API31 blur,
 * bitmap textures, sensors or an additional rendering library. Content owns its insets.
 */
@Composable
internal fun CassetteDeckHousing(
    theme: WalkmanTheme,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val edge = 8.dp.toPx()
            val topLeft = Offset(edge, edge)
            val outer = Size((size.width - edge * 2).coerceAtLeast(0f), (size.height - edge * 2).coerceAtLeast(0f))
            val radius = CornerRadius(18.dp.toPx())
            // Several broad, faint outlines soften the case shadow and stay inside bounds.
            for (layer in 5 downTo 1) {
                val shift = layer * .7.dp.toPx()
                drawRoundRect(Color.Black.copy(alpha = .025f), topLeft + Offset(shift, shift), outer,
                    radius, style = Stroke(layer * 1.1.dp.toPx()))
            }
            drawRoundRect(Color.Black.copy(alpha = .55f), topLeft + Offset(0f, 2.dp.toPx()), outer, radius)
            drawRoundRect(Brush.linearGradient(
                0f to lerp(theme.face, Color.White, .20f),
                .33f to lerp(theme.face, Color.White, .055f),
                1f to lerp(theme.face, Color.Black, .29f),
                start = topLeft, end = Offset(size.width, size.height)), topLeft, outer, radius)
            drawRoundRect(Brush.linearGradient(
                listOf(Color.White.copy(alpha = .45f), Color.Black.copy(alpha = .58f)),
                start = topLeft, end = Offset(size.width, size.height)), topLeft, outer, radius,
                style = Stroke(1.5.dp.toPx()))
            val inner = topLeft + Offset(3.dp.toPx(), 3.dp.toPx())
            drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(alpha = .13f), Color.Black.copy(alpha = .16f)),
                start = inner, end = Offset(size.width, size.height)), inner,
                Size((outer.width - 6.dp.toPx()).coerceAtLeast(0f), (outer.height - 6.dp.toPx()).coerceAtLeast(0f)),
                CornerRadius(15.dp.toPx()), style = Stroke(1.dp.toPx()))
            // Fixed, low-amplitude metal grain: no random values, texture assets or frame loop.
            val grainBounds = Path().apply { addRoundRect(RoundRect(Rect(topLeft, outer), radius)) }
            clipPath(grainBounds) {
                repeat(130) { n ->
                    val x = edge + ((n * 73) % 997) / 997f * outer.width
                    val y = edge + ((n * 149) % 991) / 991f * outer.height
                    drawLine(Color.White.copy(alpha = .023f), Offset(x, y), Offset(x + 5.dp.toPx(), y), .55.dp.toPx())
                }
            }
        }
        content()
    }
}

/** Inset cassette, drawn entirely with vectors. The label is real Compose text. */
@Composable
internal fun CassetteFace(
    theme: WalkmanTheme,
    title: String,
    @Suppress("UNUSED_PARAMETER") artist: String,
    progress: Float,
    positionMs: Long,
    rotation: State<Double>,
    durationKnown: Boolean = true,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val shell = Color(0xFF222A30)
            val window = Color(0xFF080D10)
            val rim = CornerRadius(h * .065f)
            val shellOrigin = Offset(w * .012f, h * .018f)
            val shellSize = Size(w * .976f, h * .964f)

            // Recessed well: darkness under the upper-left lip, a reflected lower-right edge.
            drawRoundRect(Color.Black.copy(alpha = .57f), cornerRadius = rim)
            drawRoundRect(Brush.linearGradient(listOf(Color.Black.copy(alpha = .76f), Color.White.copy(alpha = .13f)),
                start = Offset.Zero, end = Offset(w, h)), cornerRadius = rim, style = Stroke(2.dp.toPx()))
            drawRoundRect(Color.Black.copy(alpha = .43f), shellOrigin + Offset(1.5.dp.toPx(), 2.5.dp.toPx()), shellSize, rim)
            drawRoundRect(Brush.linearGradient(
                0f to lerp(shell, theme.accent, .045f),
                .45f to shell,
                1f to Color(0xFF12191F),
                start = shellOrigin, end = Offset(w, h)), shellOrigin, shellSize, rim)
            drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(alpha = .37f), Color.Black.copy(alpha = .72f)),
                start = shellOrigin, end = Offset(w, h)), shellOrigin, shellSize, rim, style = Stroke(1.1.dp.toPx()))
            drawRoundRect(Color.White.copy(alpha = .045f), shellOrigin + Offset(2.dp.toPx(), 2.dp.toPx()),
                Size((shellSize.width - 4.dp.toPx()).coerceAtLeast(0f), (shellSize.height - 4.dp.toPx()).coerceAtLeast(0f)),
                CornerRadius(h * .052f), style = Stroke(.7.dp.toPx()))

            // Paper edge and slight cast shadow; no duplicate title is painted on the Canvas.
            val labelOrigin = Offset(w * .065f, h * .065f)
            val labelSize = Size(w * .87f, h * .32f)
            drawRoundRect(Color.Black.copy(alpha = .32f), labelOrigin + Offset(1.dp.toPx(), 2.dp.toPx()),
                labelSize, CornerRadius(h * .018f))
            drawRoundRect(Brush.linearGradient(listOf(lerp(theme.display, Color.White, .06f), theme.display),
                start = labelOrigin, end = labelOrigin + Offset(labelSize.width, labelSize.height)),
                labelOrigin, labelSize, CornerRadius(h * .018f))
            drawLine(Color.White.copy(alpha = .50f), labelOrigin + Offset(3.dp.toPx(), .5.dp.toPx()),
                labelOrigin + Offset(labelSize.width - 3.dp.toPx(), .5.dp.toPx()), .65.dp.toPx())
            drawLine(Color.Black.copy(alpha = .16f), labelOrigin + Offset(labelSize.width, 2.dp.toPx()),
                labelOrigin + Offset(labelSize.width, labelSize.height - 2.dp.toPx()), .65.dp.toPx())

            val glassOrigin = Offset(w * .095f, h * .42f)
            val glassSize = Size(w * .81f, h * .37f)
            val glassRadius = CornerRadius(h * .055f)
            drawRoundRect(Color.Black.copy(alpha = .75f), glassOrigin - Offset(1.dp.toPx(), 1.dp.toPx()),
                Size(glassSize.width + 2.dp.toPx(), glassSize.height + 2.dp.toPx()), glassRadius)
            drawRoundRect(Brush.linearGradient(listOf(Color(0xFF05090B), Color(0xFF142026)),
                start = glassOrigin, end = glassOrigin + Offset(glassSize.width, glassSize.height)),
                glassOrigin, glassSize, glassRadius)
            drawRoundRect(Brush.linearGradient(listOf(Color.Black.copy(alpha = .82f), Color.White.copy(alpha = .23f)),
                start = glassOrigin, end = glassOrigin + Offset(glassSize.width, glassSize.height)),
                glassOrigin, glassSize, glassRadius, style = Stroke(1.dp.toPx()))

            val radius = h * .195f
            val left = Offset(w * .285f, h * .607f)
            val right = Offset(w * .715f, h * .607f)
            val packs = TransportGeometry.displayTapeRadii(progress, durationKnown)

            // Metal head sits behind the visible tape, rubber rollers flank the lower path.
            val headOrigin = Offset(w * .43f, h * .770f)
            val headSize = Size(w * .14f, h * .045f)
            drawRoundRect(Color.Black.copy(alpha = .5f), headOrigin + Offset(1.dp.toPx(), 2.dp.toPx()), headSize, CornerRadius(h * .01f))
            drawRoundRect(Brush.linearGradient(listOf(Color(0xFFAAB3B8), Color(0xFF414A50)),
                start = headOrigin, end = headOrigin + Offset(headSize.width, headSize.height)),
                headOrigin, headSize, CornerRadius(h * .01f))
            drawLine(Color(0xFF1B2328), Offset(w * .50f, h * .775f), Offset(w * .50f, h * .812f), .7.dp.toPx())
            for (x in listOf(.19f, .81f)) {
                val pinch = Offset(w * x, h * .878f)
                drawCircle(Color.Black.copy(alpha = .48f), h * .056f, pinch + Offset(1.2.dp.toPx(), 1.7.dp.toPx()))
                drawCircle(Brush.radialGradient(listOf(Color(0xFF42494C), Color(0xFF090D10)),
                    center = pinch - Offset(h * .018f, h * .018f), radius = h * .08f), h * .055f, pinch)
                drawCircle(Color(0xFFB8C1C7), h * .012f, pinch)
                val capstan = Offset(w * (if (x < .5f) x + .062f else x - .062f), h * .866f)
                drawCircle(Color.Black.copy(alpha = .50f), h * .027f, capstan + Offset(1.dp.toPx(), 1.5.dp.toPx()))
                drawCircle(Brush.radialGradient(listOf(Color(0xFFF2F4F5), Color(0xFF56616A)),
                    center = capstan - Offset(h * .009f, h * .009f), radius = h * .042f), h * .026f, capstan)
            }
            val tapePath = Path().apply {
                moveTo(left.x, left.y + radius * packs.first)
                lineTo(w * .245f, h * .846f)
                lineTo(w * .34f, h * .805f)
                lineTo(w * .66f, h * .805f)
                lineTo(w * .755f, h * .846f)
                lineTo(right.x, right.y + radius * packs.second)
            }
            // Offset shadow uses a second vector path so the highlight direction never rotates.
            val tapeShadow = Path().apply {
                moveTo(left.x + 1.dp.toPx(), left.y + radius * packs.first + 1.6.dp.toPx())
                lineTo(w * .245f + 1.dp.toPx(), h * .846f + 1.6.dp.toPx())
                lineTo(w * .34f + 1.dp.toPx(), h * .805f + 1.6.dp.toPx())
                lineTo(w * .66f + 1.dp.toPx(), h * .805f + 1.6.dp.toPx())
                lineTo(w * .755f + 1.dp.toPx(), h * .846f + 1.6.dp.toPx())
                lineTo(right.x + 1.dp.toPx(), right.y + radius * packs.second + 1.6.dp.toPx())
            }
            drawPath(tapeShadow, Color.Black.copy(alpha = .58f), style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round))
            drawPath(tapePath, Color(0xFF8D5C38), style = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round))
            drawPath(tapePath, Color(0xFFC18A59).copy(alpha = .55f), style = Stroke(.45.dp.toPx(), cap = StrokeCap.Round))
            cassetteReel(left, radius, packs.first, CassetteMechanics.counterClockwiseAngle(rotation.value, packs.first), window)
            cassetteReel(right, radius, packs.second, CassetteMechanics.counterClockwiseAngle(rotation.value, packs.second), window)

            // Continuous frosted-glass gradient only; no hard diagonal polygon or blur API.
            val glass = Path().apply { addRoundRect(RoundRect(Rect(glassOrigin, glassSize), glassRadius)) }
            clipPath(glass) {
                drawRect(Brush.linearGradient(
                    0f to Color.White.copy(alpha = .11f),
                    .35f to Color.White.copy(alpha = .045f),
                    .68f to Color.White.copy(alpha = .012f),
                    1f to Color.Transparent,
                    start = glassOrigin, end = glassOrigin + Offset(glassSize.width, glassSize.height)),
                    topLeft = glassOrigin, size = glassSize)
            }

            val counterHeight = maxOf(h * .113f, 24.dp.toPx()).coerceAtMost(h * .15f)
            val counterOrigin = Offset(w * .35f, h * .965f - counterHeight)
            val counterSize = Size(w * .30f, counterHeight)
            drawRoundRect(Color.Black.copy(alpha = .7f), counterOrigin - Offset(.5.dp.toPx(), .5.dp.toPx()),
                Size(counterSize.width + 1.dp.toPx(), counterSize.height + 1.dp.toPx()), CornerRadius(h * .012f))
            drawRoundRect(Color(0xFF080D10), counterOrigin, counterSize, CornerRadius(h * .012f))
            drawLine(Color.White.copy(alpha = .16f), counterOrigin + Offset(0f, counterSize.height),
                counterOrigin + Offset(counterSize.width, counterSize.height), .6.dp.toPx())
            listOf(Offset(w * .04f, h * .045f), Offset(w * .96f, h * .045f),
                Offset(w * .04f, h * .945f), Offset(w * .96f, h * .945f)).forEach { screw -> cassetteScrew(screw, h * .0125f) }
        }
        Box(Modifier.align(Alignment.TopCenter).padding(top = maxHeight * .065f).width(maxWidth * .87f)
            .height(maxHeight * .32f).padding(horizontal = 10.dp, vertical = 3.dp), contentAlignment = Alignment.CenterStart) {
            WordFitLabel(title.ifBlank { "Chưa chọn bài" }, theme.displayInk,
                modifier = Modifier.fillMaxSize(), testTag = "cassette-label", minFontSp = 12, maxFontSp = 22)
        }
        val counterHeight = maxOf(maxHeight * .113f, 24.dp).coerceAtMost(maxHeight * .15f)
        Box(Modifier.align(Alignment.TopCenter).padding(top = maxHeight * .965f - counterHeight).width(maxWidth * .30f)
            .height(counterHeight).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
            BasicText(clockTime(if (durationKnown) positionMs.coerceAtLeast(0) else 0),
                style = TextStyle(color = Color(0xFFE5EAF0), fontFamily = FontFamily.Monospace),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 15.sp, stepSize = 1.sp))
        }
    }
}

private fun DrawScope.cassetteScrew(center: Offset, radius: Float) {
    drawCircle(Color.Black.copy(alpha = .72f), radius * 1.25f, center)
    drawCircle(Brush.radialGradient(listOf(Color(0xFFC1CCD2), Color(0xFF4A555C)),
        center = center - Offset(radius * .38f, radius * .38f), radius = radius * 1.8f), radius, center)
    drawLine(Color(0xFF1B242B), center - Offset(radius * .62f, 0f), center + Offset(radius * .62f, 0f), .7.dp.toPx())
    drawArc(Color.White.copy(alpha = .30f), 195f, 90f, false, center - Offset(radius, radius),
        Size(radius * 2, radius * 2), style = Stroke(.55.dp.toPx()))
}

private fun DrawScope.cassetteReel(center: Offset, radius: Float, pack: Float, angle: Float, window: Color) {
    val packRadius = radius * pack
    drawCircle(Color.Black.copy(alpha = .58f), packRadius, center + Offset(1.dp.toPx(), 1.7.dp.toPx()))
    drawCircle(Brush.radialGradient(
        0f to Color(0xFF5D3923), .44f to Color(0xFFC28E5D), .76f to Color(0xFF95613C), 1f to Color(0xFF4D2E1B),
        center = center - Offset(packRadius * .25f, packRadius * .25f), radius = packRadius * 1.35f), packRadius, center)
    drawCircle(Color(0xFFD5A67A).copy(alpha = .45f), packRadius, center, style = Stroke(.55.dp.toPx()))
    repeat(12) { winding ->
        val ring = packRadius * (.42f + winding * .045f)
        if (ring > radius * .38f) drawCircle(Color.Black.copy(alpha = if (winding % 3 == 0) .19f else .085f),
            ring, center, style = Stroke(.45.dp.toPx()))
    }
    // Lighting remains fixed while only the six internal drive lugs rotate. The outer
    // silhouette is a continuous WHITE CIRCLE, never an outward Settings cog/star.
    val hubRadius = radius * .42f
    drawCircle(Color.Black.copy(alpha = .60f), hubRadius, center + Offset(.8.dp.toPx(), 1.2.dp.toPx()))
    drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFC6CED2)),
        center = center - Offset(hubRadius * .28f, hubRadius * .28f), radius = hubRadius * 1.7f), hubRadius, center)
    drawCircle(Color(0xFFF4F6F7), hubRadius, center, style = Stroke(.6.dp.toPx()))
    drawCircle(window, radius * .267f, center)
    rotate(angle, center) {
        repeat(CassetteMechanics.HUB_TEETH) { tooth ->
            rotate(tooth * 60f, center) {
                val lug = Path().apply {
                    moveTo(center.x - radius * .070f, center.y - radius * .286f)
                    lineTo(center.x + radius * .070f, center.y - radius * .286f)
                    lineTo(center.x + radius * .012f, center.y - radius * .213f)
                    lineTo(center.x - radius * .012f, center.y - radius * .213f)
                    close()
                }
                drawPath(lug, Color(0xFFF4F6F7))
            }
        }
    }
    drawCircle(window, radius * .19f, center)
    drawCircle(Color.Black.copy(alpha = .7f), radius * .19f, center, style = Stroke(.6.dp.toPx()))
    drawArc(Color.White.copy(alpha = .48f), 194f, 105f, false,
        center - Offset(hubRadius, hubRadius), Size(hubRadius * 2, hubRadius * 2), style = Stroke(.7.dp.toPx()))
}

/** Full-width landscape tape window. Circular reels use a single physical radius;
 * no scaled portrait drawing, title overlay or side metadata column is used. */
@Composable
internal fun LandscapeCassetteFace(
    theme: WalkmanTheme, progress: Float, positionMs: Long,
    rotation: State<Double>, durationKnown: Boolean,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val window = Color(0xFF080D10)
            val corner = CornerRadius(12.dp.toPx())
            val origin = Offset(2.dp.toPx(), 2.dp.toPx())
            val shellSize = Size((w - 4.dp.toPx()).coerceAtLeast(0f), (h - 4.dp.toPx()).coerceAtLeast(0f))
            drawRoundRect(Brush.linearGradient(listOf(Color(0xFF37434A), Color(0xFF141C22)),
                Offset.Zero, Offset(w,h)), origin, shellSize, corner)
            drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(alpha=.35f),Color.Black.copy(alpha=.7f)),
                Offset.Zero,Offset(w,h)), origin,shellSize,corner,style=Stroke(1.dp.toPx()))
            val glassOrigin=Offset(w*.035f,h*.065f)
            val glassSize=Size(w*.93f,h*.82f)
            val glassCorner=CornerRadius(10.dp.toPx())
            drawRoundRect(Color.Black.copy(alpha=.65f),glassOrigin-Offset(1.dp.toPx(),1.dp.toPx()),
                Size(glassSize.width+2.dp.toPx(),glassSize.height+2.dp.toPx()),glassCorner)
            drawRoundRect(Brush.linearGradient(listOf(window,Color(0xFF1B292F)),Offset.Zero,Offset(w,h)),
                glassOrigin,glassSize,glassCorner)
            drawRoundRect(Brush.linearGradient(listOf(Color.Black.copy(alpha=.8f),Color.White.copy(alpha=.18f)),
                Offset.Zero,Offset(w,h)),glassOrigin,glassSize,glassCorner,style=Stroke(1.dp.toPx()))
            val radius=minOf(h*.40f,w*.18f)
            val left=Offset(w*.22f,h*.47f)
            val right=Offset(w*.78f,h*.47f)
            val packs=TransportGeometry.displayTapeRadii(progress,durationKnown)
            val path=Path().apply {
                moveTo(left.x,left.y+radius*packs.first)
                lineTo(w*.18f,h*.84f);lineTo(w*.43f,h*.83f)
                lineTo(w*.57f,h*.83f);lineTo(w*.82f,h*.84f)
                lineTo(right.x,right.y+radius*packs.second)
            }
            val shadow=Path().apply {
                moveTo(left.x+1.dp.toPx(),left.y+radius*packs.first+2.dp.toPx())
                lineTo(w*.18f+1.dp.toPx(),h*.84f+2.dp.toPx());lineTo(w*.43f+1.dp.toPx(),h*.83f+2.dp.toPx())
                lineTo(w*.57f+1.dp.toPx(),h*.83f+2.dp.toPx());lineTo(w*.82f+1.dp.toPx(),h*.84f+2.dp.toPx())
                lineTo(right.x+1.dp.toPx(),right.y+radius*packs.second+2.dp.toPx())
            }
            for(x in listOf(.16f,.84f)) {
                val pinch=Offset(w*x,h*.83f)
                drawCircle(Color.Black.copy(alpha=.5f),h*.056f,pinch+Offset(1.dp.toPx(),2.dp.toPx()))
                drawCircle(Brush.radialGradient(listOf(Color(0xFF50595E),window),pinch-Offset(h*.02f,h*.02f),h*.09f),h*.055f,pinch)
                drawCircle(Color(0xFFC3CDD2),h*.012f,pinch)
                val capstan=Offset(w*(if(x<.5f)x+.02f else x-.02f),h*.84f)
                drawCircle(Brush.radialGradient(listOf(Color.White,Color(0xFF57646D)),capstan-Offset(1.dp.toPx(),1.dp.toPx()),h*.05f),h*.025f,capstan)
            }
            val head=Offset(w*.44f,h*.79f)
            drawRoundRect(Brush.linearGradient(listOf(Color(0xFFC3CCD1),Color(0xFF46535C)),head,head+Offset(w*.12f,h*.06f)),
                head,Size(w*.12f,h*.06f),CornerRadius(2.dp.toPx()))
            drawPath(shadow,Color.Black.copy(alpha=.5f),style=Stroke(2.6.dp.toPx(),cap=StrokeCap.Round))
            drawPath(path,Color(0xFFAE794A),style=Stroke(1.7.dp.toPx(),cap=StrokeCap.Round))
            cassetteReel(left,radius,packs.first,CassetteMechanics.counterClockwiseAngle(rotation.value,packs.first),window)
            cassetteReel(right,radius,packs.second,CassetteMechanics.counterClockwiseAngle(rotation.value,packs.second),window)
            val glass=Path().apply { addRoundRect(RoundRect(Rect(glassOrigin,glassSize),glassCorner)) }
            clipPath(glass) {
                drawRect(Brush.linearGradient(0f to Color.White.copy(alpha=.09f),.4f to Color.White.copy(alpha=.025f),1f to Color.Transparent,
                    start=glassOrigin,end=glassOrigin+Offset(glassSize.width,glassSize.height)),glassOrigin,glassSize)
            }
            val counter=Offset(w*.36f,h*.34f)
            drawRoundRect(Color.Black.copy(alpha=.6f),counter+Offset(1.dp.toPx(),2.dp.toPx()),Size(w*.28f,h*.25f),CornerRadius(6.dp.toPx()))
            drawRoundRect(Color(0xFF060B0D),counter,Size(w*.28f,h*.25f),CornerRadius(6.dp.toPx()))
            drawRoundRect(Color.White.copy(alpha=.12f),counter,Size(w*.28f,h*.25f),CornerRadius(6.dp.toPx()),style=Stroke(.8.dp.toPx()))
            listOf(Offset(w*.015f,h*.08f),Offset(w*.985f,h*.08f),Offset(w*.015f,h*.91f),Offset(w*.985f,h*.91f))
                .forEach { cassetteScrew(it,minOf(h*.018f,3.dp.toPx())) }
            drawLine(theme.accent.copy(alpha=.35f),Offset(w*.07f,h*.94f),Offset(w*.93f,h*.94f),1.dp.toPx())
        }
        Box(Modifier.align(Alignment.TopCenter).padding(top=maxHeight*.34f).width(maxWidth*.28f).height(maxHeight*.25f)
            .padding(horizontal=8.dp),contentAlignment=Alignment.Center) {
            BasicText(clockTime(if(durationKnown)positionMs else 0),
                style=TextStyle(color=Color(0xFFE5EAF0),fontFamily=FontFamily.Monospace),
                maxLines=1,overflow=TextOverflow.Ellipsis,
                autoSize=TextAutoSize.StepBased(minFontSize=9.sp,maxFontSize=24.sp,stepSize=1.sp))
        }
    }
}
