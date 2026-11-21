// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback

import androidx.media3.common.C
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Display-only spectrum of this player's PCM writes, before Android's system volume. */
object MusicSpectrum {
    private val readings = MutableStateFlow<List<Float>>(emptyList())
    val bands = readings.asStateFlow()
    private val windows = Channel<Window>(Channel.CONFLATED)
    private val lock = Any()
    @Volatile private var generation = 0L

    init {
        CoroutineScope(Dispatchers.Default).launch {
            for (window in windows) {
                val result = spectrumBands(window.samples, window.sampleRate)
                synchronized(lock) {
                    if (window.generation == generation && hasObservers) readings.value = result
                }
            }
        }
    }

    internal val hasObservers: Boolean get() = readings.subscriptionCount.value > 0

    internal fun startOutput(): Long = synchronized(lock) {
        readings.value = emptyList()
        ++generation
    }

    internal fun invalidate(output: Long): Long = synchronized(lock) {
        if (generation == output) {
            readings.value = emptyList()
            ++generation
        } else output
    }

    internal fun submit(output: Long, samples: FloatArray, sampleRate: Int) {
        windows.trySend(Window(output, samples, sampleRate))
    }

    private data class Window(val generation: Long, val samples: FloatArray, val sampleRate: Int)
}

private const val WINDOW_SIZE = 1024
private const val BAND_COUNT = 24

internal class PcmSpectrumTap(private val encoding: Int, sampleRate: Int, channels: Int) {
    private val sampleBytes = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }
    private val frameBytes = sampleBytes * channels
    private val stride = (sampleRate / 36_000).coerceAtLeast(1)
    private val analysisRate = sampleRate / stride
    private val stereo = channels >= 2
    private var output = if (frameBytes > 0 && analysisRate > 0) MusicSpectrum.startOutput() else 0L
    private var partialBytes = 0
    private var skippedFrames = 0
    private var samples = FloatArray(WINDOW_SIZE)
    private var right = if (stereo) FloatArray(WINDOW_SIZE) else null
    private var leftPower = 0f
    private var rightPower = 0f
    private var count = 0
    private var lastWindowNs = 0L

    /** Absolute reads over bytes AudioOutput accepted on this call, including partial writes. */
    fun written(buffer: ByteBuffer, start: Int) {
        val end = buffer.position()
        if (output == 0L || end <= start) return
        val offset = (frameBytes - partialBytes) % frameBytes
        partialBytes = (partialBytes + end - start) % frameBytes
        if (!MusicSpectrum.hasObservers) {
            clearWindow()
            skippedFrames = 0
            return
        }
        val now = System.nanoTime()
        if (now - lastWindowNs < 40_000_000) return
        var cursor = start + offset + skippedFrames * frameBytes
        while (cursor <= end - frameBytes && count < WINDOW_SIZE) {
            val first = sample(buffer, cursor)
            samples[count] = first
            right?.let {
                val second = sample(buffer, cursor + sampleBytes)
                it[count] = second
                leftPower += first * first
                rightPower += second * second
            }
            count++
            cursor += stride * frameBytes
        }
        if (count == WINDOW_SIZE) {
            // Keep one coherent channel across the window so opposite-phase stereo stays visible.
            val chosen = right?.takeIf { rightPower > leftPower } ?: samples
            MusicSpectrum.submit(output, chosen, analysisRate)
            if (chosen === right) right = FloatArray(WINDOW_SIZE) else samples = FloatArray(WINDOW_SIZE)
            clearWindow()
            lastWindowNs = now
            skippedFrames = 0
        } else {
            val nextFrame = end + (frameBytes - partialBytes) % frameBytes
            skippedFrames = ((cursor - nextFrame) / frameBytes).coerceAtLeast(0)
        }
    }

    fun reset() {
        if (output != 0L) output = MusicSpectrum.invalidate(output)
        clearWindow()
        skippedFrames = 0
        partialBytes = 0
        lastWindowNs = 0L
    }

    private fun clearWindow() {
        count = 0
        leftPower = 0f
        rightPower = 0f
    }

    private fun sample(buffer: ByteBuffer, at: Int): Float {
        val low = (buffer.get(at).toInt() and 255) or ((buffer.get(at + 1).toInt() and 255) shl 8)
        if (sampleBytes == 2) return low.toShort() / 32768f
        val packed = low or ((buffer.get(at + 2).toInt() and 255) shl 16)
        if (sampleBytes == 3) return ((packed shl 8) shr 8) / 8388608f
        val word = packed or (buffer.get(at + 3).toInt() shl 24)
        return if (encoding == C.ENCODING_PCM_FLOAT) Float.fromBits(word).takeIf(Float::isFinite) ?: 0f
        else word / 2147483648f
    }
}

private val hann = DoubleArray(WINDOW_SIZE) { .5 - .5 * cos(2 * PI * it / (WINDOW_SIZE - 1)) }
private val binSine = DoubleArray(WINDOW_SIZE / 2) { -sin(2 * PI * it / WINDOW_SIZE) }
private val binCosine = DoubleArray(WINDOW_SIZE / 2) { cos(2 * PI * it / WINDOW_SIZE) }

internal fun spectrumBands(samples: FloatArray, sampleRate: Int): List<Float> {
    val real = DoubleArray(WINDOW_SIZE)
    val imag = DoubleArray(WINDOW_SIZE)
    samples.forEachIndexed { index, sample ->
        real[Integer.reverse(index) ushr (32 - Integer.numberOfTrailingZeros(WINDOW_SIZE))] = sample * hann[index]
    }
    var size = 2
    while (size <= WINDOW_SIZE) {
        for (offset in 0 until WINDOW_SIZE step size) {
            for (index in 0 until size / 2) {
                val a = offset + index
                val b = a + size / 2
                val angle = index * WINDOW_SIZE / size
                val x = real[b] * binCosine[angle] - imag[b] * binSine[angle]
                val y = real[b] * binSine[angle] + imag[b] * binCosine[angle]
                real[b] = real[a] - x
                imag[b] = imag[a] - y
                real[a] += x
                imag[a] += y
            }
        }
        size *= 2
    }
    val lower = 40.0
    val upper = minOf(16_000.0, sampleRate / 2.0)
    val levels = FloatArray(BAND_COUNT)
    if (upper <= lower) return levels.toList()
    for (bin in 1 until WINDOW_SIZE / 2) {
        val frequency = bin.toDouble() * sampleRate / WINDOW_SIZE
        if (frequency !in lower..upper) continue
        val band = (ln(frequency / lower) / ln(upper / lower) * BAND_COUNT).toInt().coerceAtMost(BAND_COUNT - 1)
        val amplitude = sqrt(real[bin] * real[bin] + imag[bin] * imag[bin]) * 4 / WINDOW_SIZE
        val level = ((20 * log10(amplitude.coerceAtLeast(1e-9)) + 65) / 65).toFloat().coerceIn(0f, 1f)
        levels[band] = maxOf(levels[band], level)
    }
    return levels.toList()
}
