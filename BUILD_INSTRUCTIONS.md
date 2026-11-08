# Build instructions

## Option A — GitHub (no local setup)
1. Create a new repository on GitHub and upload the **contents** of this folder (keep `.github/`, `gradle/`, `design/`).
2. **Actions** tab → workflow **Build Bekocat APK** runs on every push (or *Run workflow*).
3. Open the finished run → **Artifacts** → `bekocat-debug-apk` → unzip:
   - `app-arm64-v8a-debug.apk` — almost every phone from 2017 on (smallest download).
   - `app-universal-debug.apk` — works on every supported device (larger).
4. Pushing a tag like `v1.0.0` also attaches the APKs to a GitHub Release (marked pre-release, debug-signed).

The build needs network access once: Gradle downloads dependencies from Google/Maven Central and the
`:ai:fetchSherpa` task downloads the sherpa-onnx AAR (28.7 MB) from Hugging Face and **verifies its SHA-256**.
`ai/libs/*.aar` is git-ignored so the repo stays small; you may also commit it to build fully offline.

## Option B — Android Studio / command line
1. Android Studio Ladybug (2024.2)+ with JDK 17 (JDK 21 also works). Android SDK 35.
2. *File → Open* this folder → Gradle sync → *Run ▶* on a device with Android 10 (API 29)+.
3. Command line:
   ```
   ./gradlew :core:timeline:test                 # 73 JVM unit tests
   ./gradlew :app:assembleDebug                  # APKs in app/build/outputs/apk/debug/
   ./gradlew :engine:connectedDebugAndroidTest   # engine tests on a connected device/emulator
   ./gradlew :app:assembleRelease                # R8-minified, unsigned unless signing env vars are set
   ```
   The build uses ~5 GB of heap (`gradle.properties`); on machines with ≤8 GB RAM close other apps.

## Pinned toolchain
| Item | Version |
|---|---|
| Gradle wrapper | 8.11.1 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.1.0 |
| Compose BOM | 2024.12.01 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 29 |
| ABIs | arm64-v8a, armeabi-v7a, x86_64 (32-bit x86 excluded: its sherpa build links a conflicting ONNX Runtime) |

## APK size
Release (R8) per-ABI: arm64 ≈ 82 MB, armeabi-v7a ≈ 59 MB, x86_64 ≈ 94 MB. Most of it is native runtimes
(ONNX Runtime 33 MB, MediaPipe LLM 26 MB, sherpa-onnx 18 MB on arm64). AI **models** are not in the APK.

## Signing (never commit keys)
- CI produces **debug-signed** APKs (`app.bekocat.dev`) for testing — not a store release.
- Release signing reads `BEKOCAT_KEYSTORE_PATH`, `BEKOCAT_KEYSTORE_PASSWORD`, `BEKOCAT_KEY_ALIAS`,
  `BEKOCAT_KEY_PASSWORD` from the environment (GitHub Secrets). No keystore or password is in this repository.
- Publishing to any store is left to the owner; nothing here uploads anywhere.
