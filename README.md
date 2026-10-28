# CloudNest-APK

Native Android client for CloudNest — a personal archive app that connects multiple
cloud accounts (Google Drive, Dropbox, OneDrive, pCloud, MEGA), splits files into
chunks, uploads/downloads them across accounts, and verifies every chunk.

Built with Kotlin + Jetpack Compose. Room for metadata, WorkManager + foreground
service for background transfers, Android Keystore for token encryption.

## Build

```bash
./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest    # unit tests
./gradlew lint                 # Android lint
./gradlew bundleRelease assembleRelease   # release outputs (step 10)
```

Requires JDK 17 and an Android SDK with platform 35 / build-tools 35.0.0.

## Release signing

The upload key never lives in this repository. Release signing is wired to
environment variables and is enabled only when they are present:

- `CN_UPLOAD_KEYSTORE_PATH`
- `CN_UPLOAD_KEYSTORE_PASSWORD`
- `CN_UPLOAD_KEY_ALIAS` (optional, defaults to `cloudnest-upload`)
- `CN_UPLOAD_KEY_PASSWORD` (optional, defaults to the keystore password)

Without them `assembleRelease` produces an unsigned release artifact.

## CI

GitHub Actions (`.github/workflows/ci.yml`) runs lint, unit tests and a debug
build on every push and pull request to `main`.
