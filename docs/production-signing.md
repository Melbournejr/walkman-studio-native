# Production Android signing

The first production release is Stage 12, version 108 / 1.4.0-audio-engine, with package ID `com.walkmanstudio.nativeplayer`. The permanent production certificate SHA-256 is `7eb99711e4f1a8d5986a47cd60641055e3e88b61247a1cc347d3f202f6126ee4`. Both APK verification and the app's OTA trust policy read the pin from `release/production.properties`.

## Owner provisioning

Use the existing owner-controlled PKCS#12 keystore and its `walkman-release` alias. Keep it outside the checkout. Restore an independent encrypted backup on a trusted machine, confirm that the restored entry can sign a disposable APK, and compare the signer's public fingerprint with the registered pin.

In GitHub, open **Settings → Environments → New environment** and create `production-signing`. Configure required reviewers and restrict deployments to the `main` branch. Once the encrypted backup has passed the restore test, set the environment variable `WALKMAN_BACKED_UP_CERTIFICATE_SHA256` to the lowercase fingerprint above. In that same environment, create these four secrets.

| Environment secret | Value |
| --- | --- |
| `WALKMAN_SIGNING_KEYSTORE_B64` | Single-line Base64 of the existing PKCS#12 file |
| `WALKMAN_SIGNING_STORE_PASSWORD` | PKCS#12 keystore password |
| `WALKMAN_SIGNING_KEY_ALIAS` | `walkman-release` |
| `WALKMAN_SIGNING_KEY_PASSWORD` | Password for that private entry, normally the same as the PKCS#12 keystore password |

With GitHub CLI authenticated on Windows, use PowerShell to stream the Base64 directly to GitHub. Replace the example path with the path to the existing file.

```powershell
$keystore = 'C:\secure\walkman-release.p12'
[Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore)) |
  gh secret set WALKMAN_SIGNING_KEYSTORE_B64 --env production-signing --repo Melbournejr/walkman-studio-native
```

Use GitHub's protected UI or the interactive `gh secret set NAME --env production-signing --repo Melbournejr/walkman-studio-native` prompt for the remaining secrets. The workflow decodes the Base64 to a restricted temporary file, checks the certificate at the configured alias, supplies passwords to the Android signing tools through environment variables, and deletes the temporary file on exit.

## Release gates

After normal source review, dispatch **Build and sign Walkman Studio Native production release** on `main`. The build job has no signing credentials. The protected signing job checks the backup attestation and key certificate, signs the optimized release, checks the packaged OTA pin and APK identity, and stages the distribution. Submit the staged distribution for normal review. Preserve the source commit in the merged history because the publisher reproduces the source archive from it.

Before dispatching the first publication, verify an upgrade on a disposable API 26+ test device or emulator. The signing workflow produces the signed Stage 12 APK and verifies a signed version 109 probe under the same production certificate. Download the probe from the separate `production-ota-probe-test-only` artifact and keep it off the public distribution channel. Independently run `apksigner verify --verbose --print-certs` on both, install 108 on the test device, then run `adb install -r NEXT.apk`. Verify that the package version increases and that a visible setting survives. Host tests cover the updater's manifest, download hashes, and same-certificate policy. On a private test feed or with an on-device test harness, validate manifest discovery and the install prompt before promoting release artifacts. Record the verification in the release review.

Dispatch **Publish Walkman Studio Native production release** after verification and select the OTA validation confirmation. The job validates the archived source, APK signer, package, version, ABI, alignment and OTA manifest. It checks the actual latest published APK. The only legacy transition permits Stage 11 to Stage 12 as a clean installation. Every later publication requires a matching production certificate and higher versionCode. Increase `versionCode`, `versionName` and `tag` in the public policy for each future release. Preserve the certificate pin.

## One-time migration on the owner's phone

1. Wait for the official production-signed Stage 12 APK. Confirm the published APK's SHA-256 and signer.
2. Confirm your music files are outside Stage 11's private app directory. Keep a separate copy of any source files you need.
3. Uninstall Stage 11. Android clears its private library database, playlists, EQ presets, settings and folder permissions. Stage 12 cannot update Stage 11 in place because the signing keys differ.
4. Install the official Stage 12 APK. Grant access to your music folders and rescan.
5. Subsequent official releases use the same production certificate and higher version codes. Verify the in-app OTA on the next release.
