# Mio Player

Mio Player is an offline Hi-Fi music player for Android 8 and later. Browse local tracks, albums, folders, favorites and playlists, then listen with the modern player or the optional cassette and vinyl displays. Audio Lab shows the source, decoder, DSP and observed Android output.

- [Official Android releases](https://github.com/Melbournejr/walkman-studio-native/releases/latest)
- [Mio design audit, migration notes and interface previews](docs/mio-player-design.md)
- [Stage 12 audio engine and supported signal paths](docs/audio-engine-upgrade.md)
- [Production signing and OTA release procedure](docs/production-signing.md)

## Build and test

Use JDK 21, Android SDK Platform 36, Build Tools 35.0.0, NDK 30.0.16248370 and CMake 3.22.1. The Gradle wrapper pins Gradle 8.13. Linux host checks also require Python 3 and a C++ compiler.

```sh
./gradlew :core:test :playback:hostNative :library:testDebugUnitTest \
  :playback:testDebugUnitTest :app:testDebugUnitTest :app:assembleRelease \
  :app:lintRelease :library:lintRelease :playback:lintRelease
```

The local release build is unsigned. Official releases use the existing protected signing workflow and include ARM64 and x86_64 libraries. The app ID is `com.walkmanstudio.nativeplayer`. Mio upgrades from the production-signed Stage 12 app using the same certificate. The older Stage 11 app used a different certificate and requires the previously documented one-time reinstall.

Mio reads music from folders you grant through Android's picker. It keeps the local library index and settings in its private data directory. The playback log export is available under **Settings → About**.

Licensed under [GPL-3.0-or-later](LICENSE). See [NOTICE](NOTICE) and the corresponding source archive supplied with each release. Earlier stage reports remain in [docs](docs).
