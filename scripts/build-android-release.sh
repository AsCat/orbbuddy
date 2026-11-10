#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
keystore=${ORBBUDDY_RELEASE_KEYSTORE:-$HOME/Library/Application Support/OrbBuddy/signing/orbbuddy-release.p12}

[[ -f $keystore ]] || { echo "Release keystore not found: $keystore" >&2; exit 1; }

if [[ -z ${ORBBUDDY_RELEASE_STORE_PASSWORD:-} ]]; then
    if ! command -v security >/dev/null 2>&1; then
        echo "Set ORBBUDDY_RELEASE_STORE_PASSWORD or use macOS Keychain." >&2
        exit 1
    fi
    ORBBUDDY_RELEASE_STORE_PASSWORD=$(security find-generic-password \
        -a AsCat/orbbuddy -s 'OrbBuddy Android release keystore' -w)
fi
export ORBBUDDY_RELEASE_KEYSTORE="$keystore" ORBBUDDY_RELEASE_STORE_PASSWORD
trap 'unset ORBBUDDY_RELEASE_STORE_PASSWORD' EXIT

if [[ -z ${ANDROID_HOME:-} && -d $HOME/Library/Android/sdk ]]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
fi

(cd "$repo_dir/android" && ./gradlew :app:testDebugUnitTest :app:assembleRelease)
apk="$repo_dir/android/app/build/outputs/apk/release/app-release.apk"
[[ -s $apk ]] || { echo "Signed APK missing: $apk" >&2; exit 1; }

if [[ -n ${ANDROID_HOME:-} ]]; then
    apksigner=$(find "$ANDROID_HOME/build-tools" -mindepth 2 -maxdepth 2 -name apksigner -type f | sort -V | tail -1)
else
    apksigner=$(command -v apksigner || true)
fi
[[ -n $apksigner ]] || { echo "apksigner is required to verify the APK." >&2; exit 1; }
"$apksigner" verify --verbose --print-certs "$apk"

if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$apk"
else
    sha256sum "$apk"
fi
