// SPDX-License-Identifier: GPL-3.0-or-later
import com.walkmanstudio.nativeplayer.core.DspConfig;
import com.walkmanstudio.nativeplayer.core.DspEngine;
import com.walkmanstudio.nativeplayer.core.ReplayGainInfo;
import com.walkmanstudio.nativeplayer.core.ReplayGainMode;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;

/** Measures steady-state stereo DSP on one host JVM thread. */
class DspHostBenchmark {
    private static final int FRAMES = 1_000_000;
    private static final int RATE = 96_000;
    private static volatile double checksum;

    public static void main(String[] args) throws Exception {
        var thread = ManagementFactory.getThreadMXBean();
        if (!thread.isCurrentThreadCpuTimeSupported()) {
            throw new IllegalStateException("Thread CPU timing is required");
        }
        thread.setThreadCpuTimeEnabled(true);
        var replayGain = new ReplayGainInfo();
        var signal = new double[1024];
        for (int i = 0; i < signal.length; i++) {
            signal[i] = Math.sin(i * 2 * Math.PI * 7 / signal.length) * .05;
        }

        for (double gain : new double[] {0.0, 6.0}) {
            var engine = new DspEngine(RATE, 2, config(gain), replayGain);
            for (int warmup = 0; warmup < 3; warmup++) run(engine, signal);
            var samples = new double[7];
            for (int i = 0; i < samples.length; i++) {
                long start = thread.getCurrentThreadCpuTime();
                run(engine, signal);
                samples[i] = (thread.getCurrentThreadCpuTime() - start) / (FRAMES * 2.0);
            }
            Arrays.sort(samples);
            System.out.printf(java.util.Locale.ROOT, "%.0f dB across 10 bands: %.2f ns/sample%n", gain, samples[3]);
        }
    }

    private static DspConfig config(double gain) throws ReflectiveOperationException {
        List<Double> bands = java.util.Collections.nCopies(10, gain);
        var flags = new Class<?>[] {boolean.class, List.class, double.class, double.class,
            ReplayGainMode.class, boolean.class, boolean.class, boolean.class};
        try {
            return DspConfig.class.getConstructor(flags)
                .newInstance(false, bands, 0.0, 0.0, ReplayGainMode.OFF, false, false, false);
        } catch (NoSuchMethodException baseline) {
            return DspConfig.class.getConstructor(Arrays.copyOf(flags, flags.length - 1))
                .newInstance(false, bands, 0.0, 0.0, ReplayGainMode.OFF, false, false);
        }
    }

    private static void run(DspEngine engine, double[] signal) {
        double sum = 0;
        for (int i = 0; i < FRAMES; i++) {
            double sample = signal[i & (signal.length - 1)];
            sum += engine.process(sample, 0) + engine.process(-sample * .5, 1);
        }
        checksum = sum;
    }
}
