#!/usr/bin/env bash
# Screenshot set for review (home, drawer, search, hidden space):
#   LAUNCHER_SERIAL=emulator-5562 scripts/shots.sh [out dir]
# Reviewed copies land in screenshots/ (the screenshot-test goldens).
set -euo pipefail
SERIAL="${LAUNCHER_SERIAL:?set LAUNCHER_SERIAL}"
OUT="${1:-$HOME/SWE/.muse-runs/launcher-shots}"
mkdir -p "$OUT"
# Only ever of ccez-launcher: another home app answers HOME otherwise.
adb -s "$SERIAL" shell cmd role get-role-holders android.app.role.HOME | grep -qx uk.ccez.launcher || { echo "ccez-launcher isn't the home app on $SERIAL"; exit 1; }
shot() { adb -s "$SERIAL" exec-out screencap -p > "$OUT/$1.png"; echo "$1"; }
home() { adb -s "$SERIAL" shell input keyevent KEYCODE_HOME; sleep 2; }
home; shot home
adb -s "$SERIAL" shell input swipe 540 1500 540 700 250; sleep 1; shot drawer
adb -s "$SERIAL" shell input text "2+3*4"; sleep 1; shot search
for i in 1 2 3 4 5; do adb -s "$SERIAL" shell input keyevent KEYCODE_DEL; done
adb -s "$SERIAL" shell input swipe 200 1000 850 1000 250; sleep 1; shot hidden
home
echo "shots in $OUT"
