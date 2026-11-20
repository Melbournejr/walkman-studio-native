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

Media3 1.11.1 skips its custom processor chain when it enables float output. The high-resolution path therefore runs DSP in a forwarding sink and retains Media3's output configuration, trimming and backpressure. The application never selects a new sample rate. Float32 represents the complete signed PCM24 sample precision and rounds PCM32 samples to Float32 precision. Decoder output remains device and codec dependent. Float output does not use dither. PCM16 dither follows the saved preference. Float DSP runs before Media3 removes encoder priming and padding, so preceding decoded samples can influence filter state at the start of a gapless track.

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

Use the existing trusted Stage 11 signing identity through its custodian. The Stage 11 GitHub workflow verifies and uploads already signed bytes. It does not have a signing step. Review builds signed by the cloud environment's debug key cannot be installed as an update to Stage 11.

After the test, review and signing gates pass, stage the release from the reviewed commit using the verified Stage 11 APK and the signed version 108 release-config APK. The checker uses the R8 mapping from the corresponding review build to verify the JNI class name.

```sh
python3 qa/prepare-audio-engine-ota.py SIGNED_STAGE12.apk VERIFIED_STAGE11.apk STAGING_DIRECTORY
```

The command validates application identity, package version, certificate continuity, ABI, native libraries and 16 KiB alignment. It writes the signed APK, corresponding source ZIP, `update.json` and `SHA256SUMS`. Publish those assets on the reviewed `stage12` tag together. The application discovers the manifest through the latest published GitHub Release and validates the manifest, download content and APK signing identity before installation.
