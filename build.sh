#!/usr/bin/env bash
#
# Clean build helper for the Maid Native experimental app.
#
# Usage:
#   ./build.sh              # clean + assemble debug APK (default)
#   ./build.sh release      # clean + assemble signed release APK
#   ./build.sh test         # clean + run unit tests
#   ./build.sh install      # clean + assemble debug + install to a connected device
#
# Honors existing JAVA_HOME / ANDROID_HOME if already exported; otherwise falls
# back to the locations used during development.
set -euo pipefail

cd "$(dirname "$0")"

# --- Toolchain -------------------------------------------------------------
# Prefer an already-exported JAVA_HOME; else try the local JDK 21.
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME:-}/bin/java" ]]; then
  if [[ -x "$HOME/.local/jdks/jdk-21/bin/java" ]]; then
    export JAVA_HOME="$HOME/.local/jdks/jdk-21"
  fi
fi

# Prefer an already-exported ANDROID_HOME; else try common local locations,
# else fall back to local.properties (sdk.dir=...).
if [[ -z "${ANDROID_HOME:-}" || ! -d "${ANDROID_HOME:-}" ]]; then
  if [[ -d "$HOME/android-sdk" ]]; then
    export ANDROID_HOME="$HOME/android-sdk"
  elif [[ -d "$HOME/Android/Sdk" ]]; then
    export ANDROID_HOME="$HOME/Android/Sdk"
  fi
fi
export ANDROID_SDK_ROOT="${ANDROID_HOME:-}"

if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:${ANDROID_HOME:-}/platform-tools:$PATH"
else
  export PATH="${ANDROID_HOME:-}/platform-tools:$PATH"
fi

echo "JAVA_HOME=${JAVA_HOME:-<unset>}"
echo "ANDROID_HOME=${ANDROID_HOME:-<unset (relying on local.properties)>}"

# --- Task selection --------------------------------------------------------
TARGET="${1:-debug}"
case "$TARGET" in
  debug)   GRADLE_TASK="clean :app:assembleDebug" ;;
  release) GRADLE_TASK="clean :app:assembleRelease" ;;
  test)    GRADLE_TASK="clean :app:testDebugUnitTest" ;;
  install) GRADLE_TASK="clean :app:assembleDebug" ;;
  *)
    echo "Unknown target '$TARGET' (expected: debug | release | test | install)" >&2
    exit 2
    ;;
esac

echo "==> ./gradlew $GRADLE_TASK --no-daemon"
# shellcheck disable=SC2086
./gradlew $GRADLE_TASK --no-daemon

# --- Post-build ------------------------------------------------------------
if [[ "$TARGET" == "install" ]]; then
  APK="app/build/outputs/apk/debug/app-arm64-v8a-debug.apk"
  echo "==> adb install -r $APK"
  adb install -r "$APK"
fi

echo "Done."
