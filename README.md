# Maid Native (experimental)

Standalone native Android app (`com.hatsyrei.maidnative`), built with Kotlin +
Jetpack Compose + Material 3. It installs **side-by-side** with the React Native
Maid app (`com.hatsyrei.maid`) so the two can be compared on one device during
the port.

See [SPEC.md](SPEC.md) for the full port specification, milestones, and the
current known-issues / parity backlog (§10). This repo is a self-contained
Gradle project, split out from the RN `maid` repo so the native port can evolve
independently.

## Prerequisites

- Android SDK (`ANDROID_HOME` set, or a `local.properties` with `sdk.dir=...`).
  Requires platform `android-37` (compileSdk; targetSdk is 36) and a matching
  build-tools release.
- JDK 21 (used locally at `~/.local/jdks/jdk-21`). Note that `JAVA_HOME` must
  point at a **JDK**, not a JRE — if `./gradlew` is invoked directly with a JRE
  on `PATH` the build fails; `build.sh` handles this for you.

The toolchain is pinned in `gradle/libs.versions.toml`: Gradle 9.5.0,
AGP 9.3.1, Kotlin 2.4.10, KSP 2.3.10, Compose BOM 2026.06.01. AGP 9 supplies
built-in Kotlin support, so the `org.jetbrains.kotlin.android` plugin is not
applied; the Kotlin and KSP plugin versions are declared on the root
`buildscript` classpath instead.

## Build & deploy

The `build.sh` helper does a **clean** build every time and auto-detects the
local toolchain (falls back to `~/.local/jdks/jdk-21` and `~/android-sdk` if
`JAVA_HOME` / `ANDROID_HOME` are not already exported):

```bash
./build.sh            # clean + debug APK  -> app/build/outputs/apk/debug/
./build.sh release    # clean + signed release APK (arm64-v8a, minified)
./build.sh test       # clean + unit tests
./build.sh install    # clean + debug APK + adb install to a connected device
```

Or drive Gradle directly:

```bash
./gradlew assembleDebug      # debug APK -> app/build/outputs/apk/debug/
./gradlew installDebug       # install to a connected device/emulator
./gradlew assembleRelease    # release APK (minified, arm64-v8a only)
```

If `ANDROID_HOME` is not exported, create `local.properties`:

```properties
sdk.dir=/home/<you>/android-sdk
```

> **Signing note:** the release build uses the git-ignored dev keystore
> `maidnative-dev.keystore` (self-signed, prototype credentials). The release
> key differs from the debug key, so run `adb uninstall com.hatsyrei.maidnative`
> before switching between debug and release builds on the same device.

## Status

Working vertical slice on-device: streaming chat against an OpenAI-compatible
endpoint, conversation-tree logic (with unit tests), Room persistence, settings,
a chat UI with message controls + branch navigation, a navigation drawer, and
Markdown rendering (incremental while streaming). Signed release APK is ~1.7 MB,
against ~20 MB for the React Native build. See [SPEC.md](SPEC.md) for the full
milestone status and the remaining parity backlog.
