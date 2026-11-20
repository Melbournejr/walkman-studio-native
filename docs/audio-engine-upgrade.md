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

Use the existing trusted Stage 11 signing identity through its custodian. The Stage 11 GitHub workflow verifies and uploads already signed bytes. It does not have a signing step. Review builds signed by the cloud environment's debug key cannot be installed as an update to Stage 11.

After the test, review and signing gates pass, stage the release from the reviewed commit using the verified Stage 11 APK and the signed version 108 release-config APK. The checker uses the R8 mapping from the corresponding release build to verify the JNI class name.

```sh
python3 qa/prepare-audio-engine-ota.py SIGNED_STAGE12.apk VERIFIED_STAGE11.apk STAGING_DIRECTORY
```

The command validates application identity, package version, certificate continuity, ABI, native libraries and 16 KiB alignment. It writes the signed APK, corresponding source ZIP, `update.json` `SHA256SUMS`, signature evidence and a checksummed `distribution/stage12` copy. Commit the staged distribution through normal review and retain the archived source commit in the merged history. The manual Stage 12 workflow verifies the APK and reproduces the source archive from that commit before publishing the release. The application discovers the manifest through the latest published GitHub Release and validates the manifest, download content and APK signing identity before installation.

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


## Protected signing workflow

An owner can configure the GitHub Actions environment `stage12-signing` for reviewed `main` runs and restrict deployment branches and reviewers in repository settings. Add these environment secrets through GitHub's protected settings using the original Stage 11 signing entry.

| Secret | Value |
| --- | --- |
| `WALKMAN_SIGNING_KEYSTORE_B64` | Base64 of the original keystore file |
| `WALKMAN_SIGNING_STORE_PASSWORD` | Keystore password |
| `WALKMAN_SIGNING_KEY_ALIAS` | Alias of the Stage 11 signing entry |
| `WALKMAN_SIGNING_KEY_PASSWORD` | Password of that signing entry |

If the existing signing custodian already has an authorized signing system, they can instead return the signed release APK for the local staging command above. This permits signature validation before the source merge without transferring the key.

Once the workflow exists on reviewed `main`, dispatch **Build and sign Walkman Studio Native Stage 12** on `main`. The build job runs without signing secrets. The signing job validates the candidate against the exact published Stage 11 APK and uploads the staged distribution only after verification. Submit that generated distribution through normal review before dispatching **Publish Walkman Studio Native Stage 12**. The signing job has read-only repository access.

## Preserve an installed Stage 11 app

Keep Stage 11 installed and retain its app data while locating the original signer. The tested in-place upgrade path requires the existing package name, a higher version code and the signer pinned by the Stage 11 updater.

The public Stage 9d, Stage 10 and Stage 11 APKs were downloaded and verified independently with `apksigner`. Their SHA-256 file digests agree with the GitHub release records. Each has a single v2 signer with subject `C=US, O=Android, CN=Android Debug` and certificate SHA-256 `2354b7de5b85a17540a1b7e43b0c66e49295ea353c9f17c33d54e9f42338dff2`. Their version codes are 105, 106 and 107, respectively.

The Stage 11 final report and APK QA record identify the original signer as the old build environment's review/install identity. The release manifest records original native source commit `2aceaa68fc2de7b6857d00f4a5f718ed434480c1`. The publication workflow reconstructed the already signed APK from checksummed parts, then uploaded the identical bytes. The public `native` source snapshot and tag both point to `cad80f2`. The current cloud review key produces certificate `78c1ff2838c2f2f51f104457d64612db5adc76ce7bf479d5d6f99b1bee6f6f33`. GitHub denied secret and environment metadata requests, so a protected copy of the original key is unconfirmed.

Ask the custodian of the original Stage 9d–11 build workstation or its backups to check the signing entry. On that trusted host, they can print only its public certificate fingerprint, with the password entered at the local prompt.

```sh
set -o pipefail
keytool -exportcert -keystore /path/to/original.keystore -alias ORIGINAL_ALIAS | sha256sum
```

If the fingerprint matches, have the custodian sign the Stage 12 release APK through the existing authorized signing setup and return only the signed APK. The staging check verifies possession through a valid APK signature. The custodian can also configure the protected environment described above for signing after review.

If the key is confirmed permanently lost, keep the installed app as the working copy while planning any migration. The published Stage 11 manifest has `allowBackup=false`. Its playback log export produces diagnostics and cannot restore the app. Stage 11 has no library or settings export. A build installed under a different identity cannot read the existing installation's private Room database, preferences or folder grants. Keep independent copies of the source music files and record visible settings and collections while Stage 11 is still usable. Any future separate installation would need fresh folder grants, a rescan, and manual re-entry of data for which Stage 11 offers no export. A future export/import feature would help data created in builds that can actually run it. There is no verified full recovery path for the current installation.
