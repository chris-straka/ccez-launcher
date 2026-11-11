#!/usr/bin/env bash
# Cold start and return-to-home, measured on LAUNCHER_SERIAL's device and
# held to perf-baseline.json (the numbers in PLAN.md). Fails when either
# gets more than 25% slower than the baseline. The Macrobenchmark
# (:macrobenchmark) gives the precise numbers; this is the release gate.
#   LAUNCHER_SERIAL=emulator-5562 scripts/perf.sh
set -euo pipefail
cd "$(dirname "$0")/.."
SERIAL="${LAUNCHER_SERIAL:?set LAUNCHER_SERIAL}"
PKG=uk.ccez.launcher
total_ms() { grep -E "^TotalTime:" | head -1 | awk '{print $2}' || true; }
wait_ms() { grep -E "^WaitTime:" | head -1 | awk '{print $2}' || true; }
# Steady state, like a day after installing: let ART compile first, or
# the first cold start pays for it. (Cold here means the process is gone.)
adb -s "$SERIAL" shell cmd package compile -m speed-profile -f "$PKG" >/dev/null 2>&1 || true
best=999999
for i in 1 2 3 4 5; do
  adb -s "$SERIAL" shell am force-stop "$PKG" >/dev/null
  t=$(adb -s "$SERIAL" shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n "$PKG/.MainActivity" | total_ms)
  if [ -n "$t" ] && [ "$t" -lt "$best" ]; then best=$t; fi
done
cold=$best
best=999999
for i in 1 2 3 4 5; do
  # Another app in front first, like closing it and landing on home.
  # (Brought to front reports WaitTime, no TotalTime.)
  adb -s "$SERIAL" shell am start -W -n com.android.settings/.Settings >/dev/null 2>&1 || true
  t=$(adb -s "$SERIAL" shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n "$PKG/.MainActivity" | wait_ms)
  if [ -n "$t" ] && [ "$t" -gt 0 ] && [ "$t" -lt "$best" ]; then best=$t; fi
done
home_again=$best
echo "cold start: ${cold} ms; return to home: ${home_again} ms (am start -W, best of 5, $SERIAL)"
python3 - "$cold" "$home_again" <<'EOF'
import json, sys
cold, home_again = int(sys.argv[1]), int(sys.argv[2])
with open("perf-baseline.json") as f:
    base = json.load(f)
failed = False
for name, got, want in [("cold_ms", cold, base["cold_ms"]), ("home_ms", home_again, base["home_ms"])]:
    if got > want * 1.25:
        print(f"perf: {name} {got} ms is over 25% slower than the baseline {want} ms")
        failed = True
sys.exit(1 if failed else 0)
EOF
echo "perf gate passed"
