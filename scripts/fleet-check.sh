#!/usr/bin/env bash
# The unit and UI tests on an emulator this script boots and shuts down
# itself, for fleet (owner: emulators run through fleet, not by hand on z):
#   fleet run --snapshot --emulator --cores 6 -- scripts/fleet-check.sh
# Each UI test is reported as it runs (am instrument -r), so a hang names
# its test; a run with no new output for STALL_SEC (default 300) is stopped.
# Results, a home screenshot and the app's log go to $FLEET_ARTIFACTS
# (else ./fleet-check-out). The core comes from ../ccez-keys, else
# $CCEZ_KEYS_DIR, else ~/SWE/ccez-keys on that machine.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${OUT:-${FLEET_ARTIFACTS:-$PWD/fleet-check-out}}"
mkdir -p "$OUT"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$([ "$(uname)" = Darwin ] && echo "$HOME/Library/Android/sdk" || echo "$HOME/Android/Sdk")}}"
export ANDROID_HOME="$SDK"
ADB="$SDK/platform-tools/adb"
if [ -z "${CCEZ_KEYS_DIR:-}" ] && [ ! -d ../ccez-keys/core ]; then
  export CCEZ_KEYS_DIR="$HOME/SWE/ccez-keys"
fi
[ -d "${CCEZ_KEYS_DIR:-../ccez-keys}/core" ] || { echo "no ccez-keys checkout for the core on $(hostname)" >&2; exit 2; }
[ -f local.properties ] || echo "sdk.dir=$SDK" > local.properties
# The emulator's ABI: arm64 on a Mac, x86_64 elsewhere (both built in).
case "$(uname -m)" in arm64|aarch64) export CCEZ_LAUNCHER_ABIS="${CCEZ_LAUNCHER_ABIS:-arm64-v8a}" ;;
  *) export CCEZ_LAUNCHER_ABIS="${CCEZ_LAUNCHER_ABIS:-arm64-v8a,x86_64}" ;; esac

echo "== unit tests"
./gradlew --console=plain -q :app:testDebugUnitTest
echo "== build"
./gradlew --console=plain -q :app:assembleDebug :app:assembleDebugAndroidTest

GPU="${GPU:-$([ "$(uname)" = Darwin ] && echo host || echo swiftshader_indirect)}"
# This app's own device where there is one (z), then f's, then the first.
AVDS="$("$SDK/emulator/emulator" -list-avds 2>/dev/null || true)"
AVD="${AVD:-$(printf '%s\n' "$AVDS" | grep -x launcher_avd || printf '%s\n' "$AVDS" | grep -x pixel36 || printf '%s\n' "$AVDS" | head -1)}"
[ -n "$AVD" ] || { echo "no virtual device on $(hostname)" >&2; exit 2; }
PORT="${EMU_PORT:-5584}"
SERIAL="emulator-$PORT"
echo "== boot $AVD as $SERIAL"
"$SDK/emulator/emulator" -avd "$AVD" -port "$PORT" -no-window -no-audio -no-boot-anim \
  -gpu "$GPU" -no-snapshot -wipe-data >"$OUT/emulator.log" 2>&1 &
emu=$!
finish() {
  "$ADB" -s "$SERIAL" logcat -d -v time >"$OUT/logcat-all.txt" 2>/dev/null || true
  grep -E 'uk\.ccez\.launcher|TestRunner|AndroidRuntime|FATAL' "$OUT/logcat-all.txt" >"$OUT/logcat.txt" 2>/dev/null || true
  "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
  sleep 3
  kill "$emu" 2>/dev/null || true
  ./gradlew --stop -q >/dev/null 2>&1 || true
}
trap finish EXIT
"$ADB" -s "$SERIAL" wait-for-device
for _ in $(seq 1 180); do
  [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
  sleep 2
done
"$ADB" -s "$SERIAL" shell settings put global window_animation_scale 0
"$ADB" -s "$SERIAL" shell settings put global transition_animation_scale 0
"$ADB" -s "$SERIAL" shell settings put global animator_duration_scale 0
"$ADB" -s "$SERIAL" shell media volume --stream 3 --set 0 >/dev/null 2>&1 || true

echo "== install as home"
"$ADB" -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$ADB" -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
"$ADB" -s "$SERIAL" shell cmd role add-role-holder android.app.role.HOME uk.ccez.launcher >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" shell pm grant uk.ccez.launcher android.permission.READ_CONTACTS >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_HOME
sleep 3
"$ADB" -s "$SERIAL" exec-out screencap -p >"$OUT/home.png" || true

echo "== UI tests"
STALL="${STALL_SEC:-300}"
"$ADB" -s "$SERIAL" shell am instrument -w -r uk.ccez.launcher.test/androidx.test.runner.AndroidJUnitRunner \
  >"$OUT/instrument.txt" 2>&1 &
run=$!
last=0; quiet=0
while kill -0 "$run" 2>/dev/null; do
  sleep 10
  now=$(wc -c <"$OUT/instrument.txt")
  if [ "$now" = "$last" ]; then quiet=$((quiet + 10)); else quiet=0; last=$now; fi
  if [ "$quiet" -ge "$STALL" ]; then
    hung=$(grep 'INSTRUMENTATION_STATUS: test=' "$OUT/instrument.txt" | tail -1 | cut -d= -f2)
    echo "stalled ${STALL}s in: $hung" | tee "$OUT/stalled.txt"
    "$ADB" -s "$SERIAL" exec-out screencap -p >"$OUT/stalled.png" || true
    "$ADB" -s "$SERIAL" shell dumpsys activity top >"$OUT/stalled-top.txt" 2>&1 || true
    kill "$run" 2>/dev/null || true
    exit 1
  fi
done
wait "$run" || true
# The runner's own summary: "OK (32 tests)", or "FAILURES!!!" then
# "Tests run: 32,  Failures: 1"; failed tests are named after "Error in".
tail -3 "$OUT/instrument.txt"
grep -E '^Error in |^[0-9]+\) ' "$OUT/instrument.txt" >"$OUT/failed.txt" || true
cat "$OUT/failed.txt"
grep -qE '^OK \([1-9][0-9]* tests?\)' "$OUT/instrument.txt"
