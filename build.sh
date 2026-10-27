#!/usr/bin/env bash
#
# Clean build helper for the Maid Native experimental app.
#
# Usage:
#   ./build.sh              # clean + assemble and sign the release APK (default)
#   ./build.sh debug        # clean + assemble debug APK
#   ./build.sh test         # clean + run unit tests
#   ./build.sh install      # clean + assemble release + install to a connected device
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
TARGET="${1:-release}"

if [[ ( "$TARGET" == "release" || "$TARGET" == "install" ) && ! -f keystore.properties ]]; then
  if [[ "$TARGET" == "install" ]]; then
    echo "keystore.properties is missing: an unsigned APK cannot be installed (see README, Signing)." >&2
    exit 1
  fi
  echo "WARNING: keystore.properties is missing; the release APK will be unsigned." >&2
fi

case "$TARGET" in
  debug)   GRADLE_TASK="clean :app:assembleDebug" ;;
  release) GRADLE_TASK="clean :app:assembleRelease" ;;
  test)    GRADLE_TASK="clean :app:testDebugUnitTest" ;;
  install) GRADLE_TASK="clean :app:assembleRelease" ;;
  *)
    echo "Unknown target '$TARGET' (expected: debug | release | test | install)" >&2
    exit 2
    ;;
esac

echo "==> ./gradlew $GRADLE_TASK --no-daemon"
# shellcheck disable=SC2086
./gradlew $GRADLE_TASK --no-daemon

# --- Signing ---------------------------------------------------------------
# apksigner rather than AGP, which cannot attach the key-rotation lineage. The
# signature is v3 only (minSdk 28) and rotated for every supported API level, so
# installs signed by the original key accept it. Alignment is kept as built, so
# F-Droid can copy this signature onto its own unsigned build.
prop() { sed -n "s/^$1=//p" keystore.properties | tail -n 1; }

sign_release() {
  local key
  for key in storeFile storePassword keyAlias keyPassword lineageFile; do
    if [[ -z "$(prop "$key")" ]]; then
      echo "keystore.properties has no $key (see keystore.properties.example)." >&2
      exit 1
    fi
  done

  local sdk="${ANDROID_HOME:-}"
  [[ -n "$sdk" ]] || sdk="$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null)"
  local apksigner
  apksigner="$(ls -d "$sdk"/build-tools/*/ 2>/dev/null | sort -V | tail -n 1)apksigner"
  if [[ ! -x "$apksigner" ]]; then
    echo "apksigner not found under $sdk/build-tools." >&2
    exit 1
  fi

  local out=app/build/outputs/apk/release
  echo "==> apksigner sign $out/app-release.apk"
  KS_PASS="$(prop storePassword)" KEY_PASS="$(prop keyPassword)" "$apksigner" sign \
    --ks "$(prop storeFile)" --ks-key-alias "$(prop keyAlias)" \
    --ks-pass env:KS_PASS --key-pass env:KEY_PASS \
    --lineage "$(prop lineageFile)" \
    --v1-signing-enabled false --v2-signing-enabled false \
    --v3-signing-enabled true --v4-signing-enabled false \
    --rotation-min-sdk-version 28 --alignment-preserved \
    --out "$out/app-release.apk" "$out/app-release-unsigned.apk"
  "$apksigner" verify --print-certs "$out/app-release.apk" | grep -E 'certificate (DN|SHA-256)'
}

if [[ ( "$TARGET" == "release" || "$TARGET" == "install" ) && -f keystore.properties ]]; then
  sign_release
fi

# --- Post-build ------------------------------------------------------------
if [[ "$TARGET" == "install" ]]; then
  APK="app/build/outputs/apk/release/app-release.apk"
  echo "==> adb install -r $APK"
  adb install -r "$APK"
fi

echo "Done."
