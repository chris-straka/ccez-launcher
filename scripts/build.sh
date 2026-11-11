#!/usr/bin/env bash
# Builds the release APK, signed with the key in ~/.config/ccez-launcher
# (made once here, never regenerated: a new key means uninstalling the
# launcher and its layout), and installs it when a device is named.
#   scripts/build.sh                          build only
#   LAUNCHER_SERIAL=<adb serial> scripts/build.sh   build + install there
#   NO_INSTALL=1 scripts/build.sh             build only
set -euo pipefail
cd "$(dirname "$0")/.."
KEYDIR="$HOME/.config/ccez-launcher"
mkdir -p "$KEYDIR"
if [ ! -f "$KEYDIR/release.jks" ]; then
  PASS="$(openssl rand -base64 24)"
  printf '%s' "$PASS" > "$KEYDIR/password"
  keytool -genkeypair -v -keystore "$KEYDIR/release.jks" -alias ccez-launcher \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" -dname "CN=ccez-launcher" >/dev/null 2>&1
  echo "made the signing key in $KEYDIR (back it up; never regenerate it)"
fi
export CCEZ_LAUNCHER_KEYSTORE="$KEYDIR/release.jks"
export CCEZ_LAUNCHER_KEY_PASSWORD="$(cat "$KEYDIR/password")"
export CCEZ_LAUNCHER_VERSION_CODE="$(git rev-list --count HEAD)"
export CCEZ_LAUNCHER_VERSION_NAME="0.1.$CCEZ_LAUNCHER_VERSION_CODE"
# The phone and the arm emulator. Intel: CCEZ_LAUNCHER_ABIS=arm64-v8a,x86_64.
export CCEZ_LAUNCHER_ABIS="${CCEZ_LAUNCHER_ABIS:-arm64-v8a}"
./gradlew --console=plain -q :app:assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
echo "built $APK ($CCEZ_LAUNCHER_VERSION_NAME)"
[ "${NO_INSTALL:-}" = 1 ] && exit 0
SERIAL="${LAUNCHER_SERIAL:-}"
[ -z "$SERIAL" ] && { echo "no LAUNCHER_SERIAL; not installed"; exit 0; }
if ! adb -s "$SERIAL" install -r "$APK" >/dev/null; then echo "install failed on $SERIAL" >&2; exit 1; fi
echo "installed on $SERIAL"
adb -s "$SERIAL" logcat -c
adb -s "$SERIAL" shell content call --uri content://uk.ccez.launcher.control --method status >/dev/null 2>&1 || true
sleep 2
if adb -s "$SERIAL" logcat -d | grep -A3 "FATAL EXCEPTION" | grep -q "uk.ccez.launcher"; then
  echo "CRASHED on start" >&2
  exit 1
fi
echo "starts"
