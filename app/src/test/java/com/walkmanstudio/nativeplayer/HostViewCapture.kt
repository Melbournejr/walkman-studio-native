// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import kotlin.math.roundToInt

/** Actual Compose View rendering through Robolectric's native Skia software Canvas.
 * WindowCapture's hardware frame-commit callback is not a reliable host boundary.
 * Compose 1.9.4's software layer path still applies rotation, clipping and draw blocks.
 * No production draw code, scene content or asserted pixels are replaced here.
 */
internal object HostViewCapture {
    fun capture(rule: ComposeContentTestRule, tag: String, view: View): Bitmap {
        rule.waitForIdle()
        val bounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val width = bounds.width.roundToInt()
        val height = bounds.height.roundToInt()
        check(width > 0 && height > 0) { "Capture node must have real measured bounds" }
        return rule.runOnIdle {
            check(view.isAttachedToWindow && view.width > 0 && view.height > 0) { "Compose View must be attached and laid out" }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            view.draw(canvas)
            bitmap
        }
    }
}
