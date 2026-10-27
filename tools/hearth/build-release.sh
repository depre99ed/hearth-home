#!/usr/bin/env bash
# Builds Hearth Home (tests included) and signs it with your key (Linux, macOS).
#   export HEARTH_KEYSTORE=~/keys/hearth.jks HEARTH_KEY_ALIAS=hearth HEARTH_KEY_PASSWORD=...
#   tools/hearth/build-release.sh
# Needs JDK 17 and the Android SDK with build-tools 34.0.0 (ANDROID_HOME). Output: out/Hearth-Home.apk.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
for v in HEARTH_KEYSTORE HEARTH_KEY_ALIAS HEARTH_KEY_PASSWORD ANDROID_HOME; do
  [ -n "${!v:-}" ] || { echo "set $v first (see the top of this script)" >&2; exit 1; }
done
tools="$ANDROID_HOME/build-tools/34.0.0"
(cd "$root/launcher" && ./gradlew --no-daemon testReleaseUnitTest assembleRelease)
mkdir -p "$root/out"
"$tools/zipalign" -f -p 4 "$root/launcher/app/build/outputs/apk/release/app-release.apk" "$root/out/aligned.apk"
"$tools/apksigner" sign --ks "$HEARTH_KEYSTORE" --ks-key-alias "$HEARTH_KEY_ALIAS" \
  --ks-pass env:HEARTH_KEY_PASSWORD --key-pass env:HEARTH_KEY_PASSWORD --v4-signing-enabled false \
  --out "$root/out/Hearth-Home.apk" "$root/out/aligned.apk"
rm "$root/out/aligned.apk"
echo "signed $root/out/Hearth-Home.apk"
