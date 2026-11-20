// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.pow

/** Optional DSD normalization, deliberately outside Source Direct / PCM DSP.
 *
 * Only [com.walkmanstudio.nativeplayer.playback.dsd.DsdExtractor] calls this after its native FIR. Values above full scale remain
 * float values: the sink / selected DSP determines their eventual treatment. The counter
 * counts decoded sample-channel values, including buffered-ahead samples and seek repeats,
 * since service creation or the last actual gain change; it is not an audible clipping meter.
 */
class DsdLevelState(initialGainDb: Int = 0) {
    private class Generation(val gainDb: Int) {
        val multiplier = 10.0.pow(gainDb / 20.0).toFloat()
        val overFullScale = AtomicLong()
    }
    private val generation = AtomicReference(Generation(validGain(initialGainDb)))

    val gainDb: Int get() = generation.get().gainDb
    val overFullScaleSamples: Long get() = generation.get().overFullScale.get()

    /** Already-buffered audio retains the setting used when it was decoded. */
    fun setGainDb(db: Int) {
        validGain(db)
        while (true) {
            val before = generation.get()
            if (before.gainDb == db) return
            if (generation.compareAndSet(before, Generation(db))) return
        }
    }

    /** Absolute access preserves buffer position / limit and never touches trailing samples.
     * Zero dB is a byte-exact bypass (also preserving negative zero and NaN payload bits).
     * The one generation snapshot per block prevents a changed setting from mixing gains
     * inside a block or attributing its counts to a newer setting.
     */
    fun applyToDsdFloat(pcm: ByteBuffer, sampleCount: Int): Long {
        require(sampleCount >= 0 && sampleCount <= pcm.limit() / Float.SIZE_BYTES)
        val current = generation.get()
        if (current.gainDb == 0) return 0
        var exceeded = 0L
        for (sample in 0 until sampleCount) {
            val at = sample * Float.SIZE_BYTES
            val output = pcm.getFloat(at) * current.multiplier
            pcm.putFloat(at, output)
            if (abs(output) > 1f) exceeded++
        }
        current.overFullScale.addAndGet(exceeded)
        return exceeded
    }

    companion object {
        val ALLOWED_GAINS_DB = listOf(0, 3, 6)
        private fun validGain(db: Int): Int {
            require(db in ALLOWED_GAINS_DB) { "DSD level must be 0, +3 or +6 dB" }
            return db
        }
    }
}
