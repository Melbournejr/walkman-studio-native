// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.core

import kotlin.math.roundToInt
import kotlin.random.Random

/** Final DSP output quantizer: two independent uniforms give TPDF dither of 2 LSB peak-to-peak.
 * One LSB is 1/32768 of full scale. Noise is added before rounding, then the integer is saturated.
 * Dither removes quantization bias away from saturation; it does not recover information already
 * lost in Media3's upstream conversion to PCM16. Source Direct must bypass this quantizer entirely.
 * A Random can be supplied for reproducible measurements; the audio path uses Random.Default.
 */
class TpdfQuantizer(private val random: Random = Random.Default) {
    fun quantize(sample: Double, enabled: Boolean): Short {
        val scaled = sample * 32768.0
        val noise = if (enabled) random.nextDouble() - random.nextDouble() else 0.0
        return (scaled + noise).roundToInt().coerceIn(-32768, 32767).toShort()
    }
}
