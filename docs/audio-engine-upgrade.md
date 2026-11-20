# Audio engine upgrade

## Verified baseline

The public latest release resolves to `stage11`, version `107 / 1.3.1-native-usb`. The remote `main` at `53a9cb8` stores the distribution metadata. The `stage11` tag and remote `native` branch at `cad80f2` match the 212 files in the verified source ZIP byte for byte. The feature branch joins both histories so previous releases and the GPL source remain accessible.

| Stage 11 asset | SHA-256 |
| --- | --- |
| APK | `0771ab03c70cc6b63544008933fda3079cfc04514769192bc10140b1e97423da` |
| Source ZIP | `0af45979b40e2061ec915f1f936ef6aef867ac1ae5d21c6bed0a08b8c9f1cb9f` |
| APK signing certificate | `2354b7de5b85a17540a1b7e43b0c66e49295ea353c9f17c33d54e9f42338dff2` |

The Gradle 8.13 wrapper runs with JDK 21, SDK 36, Build Tools 35.0.0, CMake 3.22.1 and NDK 30.0.16248370. The application uses `:core`, `:library` and `:playback`. Stage 11 performed DSP after Media3 downconverted decoder PCM to PCM16. Its separate Source Direct sink enabled Media3 float output and removed the custom processor. DSD extraction used the native Kaiser FIR to emit PCM float at 88.2 kHz.

## Signal path

| Input | Processing | Media3 sink |
| --- | --- | --- |
| PCM16, DSP enabled | Media3 trimming/channel mapping, EQ/ReplayGain/preamp/headroom, soft limiter if enabled, final PCM16 quantizer | PCM16 |
| PCM24, PCM32 or Float32, DSP enabled | Decode to double, apply DSP, encode as Float32 | Float32 with Media3 trimming and channel mapping |
| High-resolution PCM when Media3 rejects float | Media3 PCM16 conversion, then the PCM16 DSP path | PCM16 at the decoder rate |
| Source Direct | Decoder PCM or unity-gain DSD-to-PCM | Media3's PCM16 or high-resolution float path without custom processors |

Media3 1.11.1 skips its custom processor chain when it enables float output. The high-resolution path therefore runs DSP in a forwarding sink and retains Media3's output configuration, trimming and backpressure. The application never selects a new sample rate. Float32 represents the complete signed PCM24 sample precision and rounds PCM32 samples to Float32 precision. Decoder output remains device and codec dependent. Float output does not use dither. PCM16 dither follows the saved preference when the DSP changes samples. A dry PCM16 chain preserves the input codes. Float DSP runs before Media3 removes encoder priming and padding, so preceding decoded samples can influence filter state at the start of a gapless track.

The fixed-coefficient EQ banks crossfade over 10 ms. New edits during a crossfade replace the next pending target. The optional zero-latency soft knee starts at approximately −0.9 dBFS and compresses instantaneous peaks toward full scale. It defaults off and works independently of the existing EQ/ReplayGain headroom calculation. Source Direct disables it and uses the saved DSD gain only after returning to DSP playback.

DSD64, DSD128 and DSD256 use the existing parser and DSD-to-PCM path. DoP encapsulates DSD in PCM frames for a compatible DAC. Native DSD sends the DSD stream through a compatible transport. This engine converts DSD to PCM for Android AudioTrack. USB telemetry distinguishes the Android mixer match, decoder/AudioTrack format match, actual routed device and application DSP bypass. Hardware bit-perfect verification remains unavailable. Compressed offload stays disabled in the player.

## Verification and release gate

Run the aggregate checks with the environment activated.

```sh
./gradlew --console=plain --continue --max-workers=2 \
  -Dwalkman.test.userHome=/workspace/.walkman-env/java-user \
  -Drobolectric.dependency.repo.url=https://repo1.maven.org/maven2 \
  :core:test :playback:hostNative :library:testDebugUnitTest \
  :playback:testDebugUnitTest :app:testDebugUnitTest \
  :app:assembleRelease :app:assembleReview \
  :app:lintReview :app:lintRelease :library:lintRelease :playback:lintRelease
```

Release signing, certificate continuity and the one-time migration are covered in [production signing](production-signing.md).

## Host performance

The C++ FIR comparison used seven alternating runs of the Stage 11 and upgraded `dsd-host-test` binaries on the same shared x86_64 host, using the same compiler options. Each run decodes two seconds of stereo DSD into 88.2 kHz PCM. The FIR benchmark excludes JNI and input I/O.

| Input | Stage 11 median | Upgrade median | Maximum error vs. Float64 reference |
| --- | ---: | ---: | ---: |
| DSD64 | 120.6 ms | 70.2 ms | 2.37 × 10⁻⁸ FS |
| DSD128 | 287.8 ms | 113.8 ms | 1.46 × 10⁻⁸ FS |
| DSD256 | 562.9 ms | 405.6 ms | 9.39 × 10⁻⁹ FS |

The native host test also checks bit order, stereo isolation, full scale, alternating-bit silence and split-buffer equivalence. It measures passband ripple below 0.015 dB through 20 kHz and sampled stopband attenuation above 70 dB from 44.1 to 100 kHz.

Run `java -cp "$KOTLIN_STDLIB:core/build/libs/core.jar" qa/DspHostBenchmark.java` after building `:core:jar`. Use the Stage 11 core JAR on the same class path for comparison. The benchmark measures per-thread CPU time over seven steady-state runs, after three warmups.

| 96 kHz stereo EQ | Stage 11 | Upgrade |
| --- | ---: | ---: |
| All bands at 0 dB | 23.73 ns/sample | 4.86 ns/sample |
| All bands at +6 dB | 23.34 ns/sample | 25.52 ns/sample |

Skipping identity filters speeds up the flat EQ. The dense preset costs 2.18 ns/sample more with the new filter-bank and state handling. Neither benchmark measures AudioTrack, ARM hardware, device power, or end-to-end playback latency.


See [production signing](production-signing.md) for the current procedure. The historical Stage 11 publisher reconstructed and uploaded already-signed APK bytes. Its signing certificate is retained only as a reference for the one-time migration.
