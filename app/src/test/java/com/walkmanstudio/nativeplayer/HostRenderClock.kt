// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import org.robolectric.annotation.internal.DoNotInstrument

/** Real host wall clock only; never Android uptime, CPU time, or a device FPS measurement.
 * Keeping the System.nanoTime call outside Robolectric instrumentation makes the timing
 * boundary explicit even if benchmark helpers are moved into instrumented packages later.
 */
@DoNotInstrument
internal object HostRenderClock {
    fun nanoTime(): Long = System.nanoTime()
}
