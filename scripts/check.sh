#!/usr/bin/env bash
# The quality gates on the named device (emulator only in sessions):
# unit tests, then the Compose UI tests, then screenshots, then perf.
#   LAUNCHER_SERIAL=emulator-5562 scripts/check.sh
set -euo pipefail
cd "$(dirname "$0")/.."
SERIAL="${LAUNCHER_SERIAL:?set LAUNCHER_SERIAL}"
export ANDROID_SERIAL="$SERIAL"
./gradlew --console=plain :app:testDebugUnitTest
./gradlew --console=plain :app:assembleDebug :app:assembleDebugAndroidTest
# Debug and release sign differently: an installed release would make the
# test install fail (and this Gradle can call that success with 0 tests).
adb -s "$SERIAL" shell cmd role remove-role-holder android.app.role.HOME uk.ccez.launcher >/dev/null 2>&1 || true
adb -s "$SERIAL" uninstall uk.ccez.launcher >/dev/null 2>&1 || true
adb -s "$SERIAL" install app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb -s "$SERIAL" shell cmd role add-role-holder android.app.role.HOME uk.ccez.launcher >/dev/null 2>&1 || true
adb -s "$SERIAL" shell pm grant uk.ccez.launcher android.permission.READ_CONTACTS >/dev/null 2>&1 || true
./gradlew --console=plain :app:connectedDebugAndroidTest
python3 - <<'PYEOF'
import re, sys
s = open("app/build/reports/androidTests/connected/debug/index.html").read()
n = int(re.findall(r'<div class="counter">(\d+)</div>', s)[0])
print(f"instrumented tests ran: {n}")
sys.exit(0 if n > 0 else 1)
PYEOF
LAUNCHER_SERIAL="$SERIAL" scripts/shots.sh
NO_INSTALL=1 scripts/build.sh >/dev/null
adb -s "$SERIAL" shell cmd role remove-role-holder android.app.role.HOME uk.ccez.launcher >/dev/null 2>&1 || true
adb -s "$SERIAL" uninstall uk.ccez.launcher >/dev/null 2>&1 || true
adb -s "$SERIAL" install app/build/outputs/apk/release/app-release.apk >/dev/null
adb -s "$SERIAL" shell cmd role add-role-holder android.app.role.HOME uk.ccez.launcher >/dev/null 2>&1 || true
LAUNCHER_SERIAL="$SERIAL" scripts/perf.sh
echo "all gates passed"
