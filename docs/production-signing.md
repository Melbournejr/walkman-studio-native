# Production Android signing

The first production release is Stage 12, version 108 / 1.4.0-audio-engine, with package ID `com.walkmanstudio.nativeplayer`. The production pin in `release/production.properties` remains blank until the owner provisions the key. Builds with a blank pin skip OTA discovery, and the signing and publication jobs fail before using credentials or publishing assets.

## Owner provisioning

Generate the key on an owner-controlled workstation using JDK 21. Choose an encrypted location outside the source checkout. Use a password manager to create and store a strong, unique keystore password. At the local prompt, enter that password. PKCS#12 uses the same password for the keystore and its private entry.

```sh
umask 077
keytool -genkeypair -storetype PKCS12 \
  -keystore /secure/local/path/walkman-production.p12 \
  -alias walkman-production -keyalg RSA -keysize 4096 -sigalg SHA256withRSA \
  -validity 10000 -dname 'CN=Walkman Studio Native Production'
```

Make an independently stored encrypted backup. Restore it on a trusted machine and confirm the restored entry can sign a disposable APK. Compare its public fingerprint with the original. Keep the key and password inside owner-controlled storage and protected GitHub settings.

```sh
set -o pipefail
keytool -exportcert -storetype PKCS12 \
  -keystore /secure/local/path/walkman-production.p12 -alias walkman-production | sha256sum
```

Commit only that lowercase SHA-256 in `release/production.properties`. In GitHub, configure the `production-signing` environment with required reviewers and deployments restricted to reviewed `main`. After the backup restore test, set the environment variable `WALKMAN_BACKED_UP_CERTIFICATE_SHA256` to the same public fingerprint. Add four environment secrets.

| GitHub secret | Owner-provided value |
| --- | --- |
| `WALKMAN_SIGNING_KEYSTORE_B64` | Base64 of the PKCS#12 file |
| `WALKMAN_SIGNING_STORE_PASSWORD` | Keystore password |
| `WALKMAN_SIGNING_KEY_ALIAS` | `walkman-production` |
| `WALKMAN_SIGNING_KEY_PASSWORD` | Same password as the PKCS#12 keystore |

If GitHub CLI is already authorized on the workstation, the keystore can be sent directly without printing it or creating a plaintext Base64 file.

```sh
base64 < /secure/local/path/walkman-production.p12 | gh secret set WALKMAN_SIGNING_KEYSTORE_B64 \
  --env production-signing --repo Melbournejr/walkman-studio-native
```

Use GitHub's protected UI or the interactive CLI prompts for the other secrets.

## Release gates

After normal source review, dispatch **Build and sign Walkman Studio Native production release** on `main`. The build job has no signing credentials. The protected signing job checks the backup attestation and key certificate, signs the optimized release, checks the packaged OTA pin and APK identity, and stages the distribution. Submit the staged distribution for normal review. Preserve the source commit in the merged history because the publisher reproduces the source archive from it.

Before dispatching the first publication, verify an upgrade on a disposable API 26+ test device or emulator. Use the protected signer to prepare two candidates on separately reviewed revisions, one at version 108 and a test candidate at a higher version code with the same pin. Do not publish the test candidate. Independently run `apksigner verify --verbose --print-certs` on both, install 108 on the test device, then run `adb install -r NEXT.apk`. Verify that the package version increases and that a visible setting survives. Host tests cover the updater's manifest, download hashes, and same-certificate policy. On a private test feed or with an on-device test harness, validate manifest discovery and the install prompt before promoting release artifacts. Record the verification in the release review.

Dispatch **Publish Walkman Studio Native production release** after verification and select the OTA validation confirmation. The job validates the archived source, APK signer, package, version, ABI, alignment and OTA manifest. It checks the actual latest published APK. The only legacy transition permits Stage 11 to Stage 12 as a clean installation. Every later publication requires a matching production certificate and higher versionCode. Increase `versionCode`, `versionName` and `tag` in the public policy for each future release. Preserve the certificate pin.

## One-time migration on the owner's phone

1. Wait for the official production-signed Stage 12 APK. Confirm the published APK's SHA-256 and signer.
2. Confirm your music files are outside Stage 11's private app directory. Keep a separate copy of any source files you need.
3. Uninstall Stage 11. Android clears its private library database, playlists, EQ presets, settings and folder permissions. Stage 12 cannot update Stage 11 in place because the signing keys differ.
4. Install the official Stage 12 APK. Grant access to your music folders and rescan.
5. Subsequent official releases use the same production certificate and higher version codes. Verify the in-app OTA on the next release.
