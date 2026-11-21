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

## Publishing a new version

Increase `versionCode`, `versionName` and `tag` in `release/production.properties` and preserve the certificate pin. A push to `main` that changes this file starts **Build and sign Walkman Studio Native production release**. The build job runs the automated tests and lint without signing credentials. The protected signing job checks the backup attestation and certificate before signing the APK and saving the verified distribution artifact.

A successful signing run starts **Publish Walkman Studio Native production release**. The protected publisher verifies the source commit, signer, package, version, ABI, alignment and OTA metadata against the signing artifact and latest public release. Later uploads require the production signer and a higher version code. GitHub's `production-signing` environment continues to enforce its deployment approval rules. To retry publication of an existing signed artifact, run the publisher on `main` with its `signing_run_id`.

## One-time migration on the owner's phone

1. Wait for the official production-signed Stage 12 APK. Confirm the published APK's SHA-256 and signer.
2. Confirm your music files are outside Stage 11's private app directory. Keep a separate copy of any source files you need.
3. Uninstall Stage 11. Android clears its private library database, playlists, EQ presets, settings and folder permissions. Stage 12 cannot update Stage 11 in place because the signing keys differ.
4. Install the official Stage 12 APK. Grant access to your music folders and rescan.
5. Subsequent official releases use the same production certificate and higher version codes. Verify the in-app OTA on the next release.
