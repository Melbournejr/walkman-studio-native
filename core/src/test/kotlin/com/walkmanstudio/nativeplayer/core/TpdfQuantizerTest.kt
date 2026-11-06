// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.core

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class TpdfQuantizerTest {
    @Test fun disabledIsExactlyThePreviousRoundAndSaturateOperation() {
        val quantizer = TpdfQuantizer(Random(8101))
        val values = listOf(-2.0, -1.0, -0.7, -0.0, 0.0, 0.5, 1.0, 2.0) +
            List(10000) { (it - 5000.25) / 32768.0 }
        values.forEach { sample ->
            assertEquals((sample * 32768.0).roundToInt().coerceIn(-32768, 32767).toShort(), quantizer.quantize(sample, false))
        }
    }

    @Test fun silenceHasZeroMeanAndExpectedTpdfQuantizedDistribution() {
        val quantizer = TpdfQuantizer(Random(8102))
        val counts = IntArray(3)
        val n = 1_000_000
        repeat(n) {
            val sample = quantizer.quantize(0.0, true).toInt()
            assertTrue(sample in -1..1)
            counts[sample + 1]++
        }
        val mean = (counts[2] - counts[0]).toDouble() / n
        val power = (counts[2] + counts[0]).toDouble() / n
        // Two uniforms give triangular noise; rounding gives P(-1,0,+1)=(1/8,3/4,1/8).
        assertEquals(0.0, mean, 0.003)
        assertEquals(0.25, power, 0.003)
        assertEquals(0.125, counts[0].toDouble() / n, 0.003)
        assertEquals(0.750, counts[1].toDouble() / n, 0.003)
        assertEquals(0.125, counts[2].toDouble() / n, 0.003)
        println("TPDF silence n=$n; meanLsb=$mean; powerLsb2=$power; counts=${counts.toList()}")
    }

    @Test fun fractionalPositiveAndNegativeDcLevelsKeepTheirMeanWithinPointZeroZeroFiveLsb() {
        val quantizer = TpdfQuantizer(Random(8103))
        val n = 262144
        for (levelLsb in listOf(-12345.375, -0.25, 0.25, 12345.375)) {
            var sum = 0L
            repeat(n) { sum += quantizer.quantize(levelLsb / 32768.0, true).toLong() }
            val error = sum.toDouble() / n - levelLsb
            assertEquals("DC=$levelLsb LSB", 0.0, error, 0.005)
            println("TPDF DC n=$n; inputLsb=$levelLsb; meanErrorLsb=$error")
        }
    }

    @Test fun sineMeanAndLevelRemainUnbiasedAwayFromSaturation() {
        val quantizer = TpdfQuantizer(Random(8104))
        val n = 480000 // 10 seconds, exactly 10000 periods; deterministic host calculation.
        var errorSum = 0.0
        var inputPower = 0.0
        var outputPower = 0.0
        repeat(n) { i ->
            val sample = 0.25 * sin(2.0 * PI * 1000.0 * i / 48000.0)
            val output = quantizer.quantize(sample, true).toDouble() / 32768.0
            errorSum += (output - sample) * 32768.0
            inputPower += sample * sample
            outputPower += output * output
        }
        assertEquals(0.0, errorSum / n, 0.004)
        assertEquals(1.0, outputPower / inputPower, 0.00001)
        println("TPDF sine n=$n; meanErrorLsb=${errorSum / n}; outputInputPowerRatio=${outputPower / inputPower}")
    }

    @Test fun independentSuccessiveSamplesDoNotShareNoiseAndAlwaysStayWithinPcm16Bounds() {
        val quantizer = TpdfQuantizer(Random(8105))
        val n = 262144
        var cross = 0L
        repeat(n) {
            val left = quantizer.quantize(0.0, true).toInt()
            val right = quantizer.quantize(0.0, true).toInt()
            cross += left * right
        }
        assertEquals(0.0, cross.toDouble() / n, 0.003)
        repeat(10000) {
            assertEquals(32767, quantizer.quantize(1.0, true).toInt())
            assertTrue(quantizer.quantize(-1.0, true).toInt() in -32768..-32767)
        }
    }

    @Test fun disabledQuantizationDoesNotConsumeRandomSequence() {
        val used = TpdfQuantizer(Random(8106))
        val fresh = TpdfQuantizer(Random(8106))
        repeat(123) { used.quantize(0.123, false) }
        repeat(10000) { assertEquals(fresh.quantize(0.0, true), used.quantize(0.0, true)) }
    }
}
