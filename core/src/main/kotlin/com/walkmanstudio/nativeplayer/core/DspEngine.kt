// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.core

import kotlin.math.*

enum class ReplayGainMode { OFF, TRACK, ALBUM }
data class ReplayGainInfo(val trackGain: Double? = null, val albumGain: Double? = null, val trackPeak: Double? = null, val albumPeak: Double? = null)
data class DspConfig(val sourceDirect: Boolean = true, val bands: List<Double> = List(10) { 0.0 },
    val clearBass: Double = 0.0, val preamp: Double = 0.0, val replayGain: ReplayGainMode = ReplayGainMode.OFF, val preventClipping: Boolean = true,
    val dither: Boolean = true, val limiter: Boolean = false) {
    init {
        require(bands.size == 10 && bands.all { it.isFinite() && it in -12.0..12.0 })
        require(clearBass.isFinite() && clearBass in 0.0..12.0 && preamp.isFinite() && preamp in -24.0..12.0)
    }
    companion object { val FREQUENCIES = listOf(31,62,125,250,500,1000,2000,4000,8000,16000) }
}

/** RBJ Audio EQ Cookbook, transposed direct form II, independent state per channel.
 * Double coefficients/state; no allocation per sample. Fixed-coefficient filter banks crossfade over 10 ms.
 * The application bypasses this engine entirely for Source Direct.
 */
class DspEngine(private val sampleRate: Int, private val channels: Int, config: DspConfig, tags: ReplayGainInfo = ReplayGainInfo()) {
    init { require(sampleRate in 8000..768000 && channels in 1..32) }
    private class FilterBank(channels: Int) {
        val coefficients = DoubleArray(55)
        private val state1 = Array(channels) { DoubleArray(11) }
        private val state2 = Array(channels) { DoubleArray(11) }
        private val active = BooleanArray(11)
        private var gain = 1.0

        fun configure(target: DoubleArray, newGain: Double) {
            target.copyInto(coefficients)
            gain = newGain
            for (i in active.indices) {
                active[i] = coefficients[i * 5] != 1.0 || coefficients[i * 5 + 1] != 0.0
            }
            reset()
        }

        fun process(input: Double, channel: Int): Double {
            val first = state1[channel]
            val second = state2[channel]
            var x = input * gain
            for (i in active.indices) {
                if (!active[i]) continue
                val k = i * 5
                val y = coefficients[k] * x + first[i]
                val z1 = coefficients[k + 1] * x - coefficients[k + 3] * y + second[i]
                val z2 = coefficients[k + 2] * x - coefficients[k + 4] * y
                first[i] = if (abs(z1) < 1e-30) 0.0 else z1
                second[i] = if (abs(z2) < 1e-30) 0.0 else z2
                x = y
            }
            return x
        }

        fun reset() {
            state1.forEach { it.fill(0.0) }
            state2.forEach { it.fill(0.0) }
        }
    }

    private var current = FilterBank(channels)
    private var incoming = FilterBank(channels)
    private val target = DoubleArray(55)
    private var targetGain = 1.0
    private val transitionFrames = max(32, sampleRate / 100)
    private var transitionFrame = 0
    private var pending = false
    private var limiter = config.limiter
    private var bypass = config.sourceDirect
    var appliedGainDb = 0.0; private set
    var clippedSamples = 0L; private set
    init { update(config,tags,initial = true) }
    fun update(config: DspConfig, tags: ReplayGainInfo, initial: Boolean = false) {
        bypass = config.sourceDirect
        limiter = config.limiter
        for (i in 0..9) {
            val frequency = DspConfig.FREQUENCIES[i].toDouble()
            val values = if (frequency >= sampleRate * 0.499 || config.sourceDirect) identity() else peak(frequency, config.bands[i])
            values.copyInto(target, i * 5)
        }
        (if (config.sourceDirect) identity() else shelf(80.0,config.clearBass)).copyInto(target,50)
        val gain = when (config.replayGain) {
            ReplayGainMode.OFF -> 0.0
            ReplayGainMode.TRACK -> tags.trackGain ?: 0.0
            ReplayGainMode.ALBUM -> tags.albumGain ?: tags.trackGain ?: 0.0
        }.takeIf { it.isFinite() && it in -60.0..60.0 } ?: 0.0
        val peak = when (config.replayGain) {
            ReplayGainMode.OFF -> 1.0
            ReplayGainMode.TRACK -> tags.trackPeak ?: 1.0
            ReplayGainMode.ALBUM -> tags.albumPeak ?: tags.trackPeak ?: 1.0
        }.takeIf { it.isFinite() && it > 0 && it <= 64 } ?: 1.0
        var db = if (config.sourceDirect) 0.0 else config.preamp + gain
        if (!config.sourceDirect && config.preventClipping) {
            var response = 0.0
            for (i in 0..256) response = max(response,responseDb(20.0 * (sampleRate / 40.0).pow(i / 256.0),target))
            db -= max(0.0,db + 20.0 * log10(peak) + response)
        }
        appliedGainDb = db; targetGain = 10.0.pow(db / 20.0)
        if (initial) {
            current.configure(target, targetGain)
        } else {
            // Keep the running transition intact and coalesce newer edits into its successor.
            pending = true
        }
    }

    fun process(input: Double, channel: Int): Double {
        if (bypass) return input
        if (channel == 0) {
            if (transitionFrame == transitionFrames) {
                val old = current
                current = incoming
                incoming = old
                transitionFrame = 0
            }
            if (transitionFrame == 0 && pending) {
                incoming.configure(target, targetGain)
                pending = false
                transitionFrame = 1
            } else if (transitionFrame > 0) {
                transitionFrame++
            }
        }

        val sample = if (input.isFinite()) input else 0.0
        var x = current.process(sample, channel)
        if (transitionFrame > 0) {
            val mix = transitionFrame.toDouble() / transitionFrames
            x += (incoming.process(sample, channel) - x) * mix
        }
        if (!x.isFinite()) {
            reset()
            return 0.0
        }
        if (abs(x) > 1.0) clippedSamples++
        if (limiter && abs(x) > 0.9) {
            val excess = abs(x) - 0.9
            return Math.copySign(1.0 - 0.01 / (0.1 + excess), x)
        }
        return x.coerceIn(-1.0, 1.0)
    }

    fun reset() {
        current.reset()
        incoming.reset()
    }

    private fun identity() = doubleArrayOf(1.0,0.0,0.0,0.0,0.0)
    private fun peak(frequency: Double, db: Double): DoubleArray {
        if (db == 0.0) return identity()
        val a = 10.0.pow(db / 40); val omega = 2 * PI * frequency / sampleRate
        val alpha = sin(omega) / (2 * sqrt(2.0)); val c = cos(omega); val a0 = 1 + alpha / a
        return doubleArrayOf((1+alpha*a)/a0,-2*c/a0,(1-alpha*a)/a0,-2*c/a0,(1-alpha/a)/a0)
    }
    private fun shelf(frequency: Double, db: Double): DoubleArray {
        if (db == 0.0) return identity()
        val a = 10.0.pow(db / 40); val omega = 2 * PI * frequency / sampleRate
        val c = cos(omega); val alpha = sin(omega) / 2 * sqrt(2.0); val t = 2 * sqrt(a) * alpha
        val a0 = (a+1)+(a-1)*c+t
        return doubleArrayOf(a*((a+1)-(a-1)*c+t)/a0,2*a*((a-1)-(a+1)*c)/a0,a*((a+1)-(a-1)*c-t)/a0,
            -2*((a-1)+(a+1)*c)/a0,((a+1)+(a-1)*c-t)/a0)
    }
    private fun responseDb(frequency: Double, c: DoubleArray): Double {
        val omega = 2 * PI * frequency.coerceAtMost(sampleRate * 0.499) / sampleRate
        val cos1 = cos(omega); val sin1 = sin(omega); val cos2 = cos(2*omega); val sin2 = sin(2*omega)
        var result = 0.0
        for (i in 0..10) {
            val k=i*5; val nr=c[k]+c[k+1]*cos1+c[k+2]*cos2; val ni=-c[k+1]*sin1-c[k+2]*sin2
            val dr=1+c[k+3]*cos1+c[k+4]*cos2; val di=-c[k+3]*sin1-c[k+4]*sin2
            result += 10 * log10(((nr*nr+ni*ni)/(dr*dr+di*di)).coerceAtLeast(1e-30))
        }
        return result
    }
}
