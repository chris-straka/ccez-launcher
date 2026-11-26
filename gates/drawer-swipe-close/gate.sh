#!/usr/bin/env bash
# Gate for gates/drawer-swipe-close/goal.md: a swipe down on the drawer's
# list closes the drawer whenever the list is at its top.
#
# Runs the repo's own checks, unchanged (scripts/fleet-check.sh: unit tests,
# the build, every UI test on an emulator it boots and shuts down), then
# reads the UI tests' results for DrawerSwipeCloseTest by name. Through
# fleet, from the repo (fleet's emulator machine):
#   fleet run --snapshot --emulator --cores 6 -- bash gates/drawer-swipe-close/gate.sh
# Prints PASS or FAIL per check (a check that can't run is a FAIL) and ends
# with GATE PASS (exit 0) or GATE FAIL (exit 1).
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 2
export LC_ALL=C
# Fleet keeps only the files right in $FLEET_ARTIFACTS (not in folders).
OUT="${FLEET_ARTIFACTS:-$PWD/gate-out}"
mkdir -p "$OUT"
rm -f "$OUT/fleet-check.log" "$OUT/instrument.txt" "$OUT/stalled.txt" "$OUT/failed.txt"
# The 32 UI tests before this gate, plus DrawerSwipeCloseTest's 7. Two
# of the 32 fail on main on fleet's emulator: known-failures.txt.
UI_TESTS_MIN=39
FAILS=0
pass() { echo "PASS  $1"; }
fail() {
  echo "FAIL  $1"
  FAILS=$((FAILS + 1))
}
detail() { sed 's/^/      /'; }

# The app's own code never looks for the tests or their padding.
hits=$(grep -rnE 'DrawerSwipeCloseTest|drawer-swipe-pad|InstrumentationRegistry|androidx\.test|isRunningTest|isRunningInTest' app/src/main 2>/dev/null)
if [ -z "$hits" ]; then
  pass "app code doesn't look for the tests"
else
  fail "app code looks for the tests:"
  printf '%s\n' "$hits" | head -5 | detail
fi

OUT="$OUT" bash scripts/fleet-check.sh >"$OUT/fleet-check.log" 2>&1
rc=$?
echo "      (scripts/fleet-check.sh exited $rc; its log is $OUT/fleet-check.log)"

if grep -qx '== build' "$OUT/fleet-check.log"; then
  pass "unit tests (scripts/fleet-check.sh)"
else
  fail "unit tests failed or didn't run (scripts/fleet-check.sh)"
  grep -E 'FAILED|Test.*>|error:|e: ' "$OUT/fleet-check.log" | head -15 | detail
  tail -15 "$OUT/fleet-check.log" | detail
fi
if grep -q '^== boot ' "$OUT/fleet-check.log"; then
  pass "app and test APKs build"
else
  fail "app and test APKs didn't build"
  grep -E '^e: |error:|FAILURE|What went wrong' "$OUT/fleet-check.log" | head -15 | detail
fi
if [ -f "$OUT/stalled.txt" ]; then
  fail "UI tests stalled: $(cat "$OUT/stalled.txt")"
elif [ -s "$OUT/instrument.txt" ] && grep -qE '^(OK \(|FAILURES!!!|Tests run: )' "$OUT/instrument.txt"; then
  pass "UI tests ran to the end"
else
  fail "UI tests didn't run to the end"
  tail -15 "$OUT/fleet-check.log" | detail
fi

python3 - "$OUT/instrument.txt" "$UI_TESTS_MIN" gates/drawer-swipe-close/known-failures.txt <<'PY'
import re
import sys

path, ui_min, known_path = sys.argv[1], int(sys.argv[2]), sys.argv[3]
try:
    text = open(path, errors="replace").read()
except OSError:
    text = ""

# am instrument -r: INSTRUMENTATION_STATUS: key=value lines (a stack runs on
# over several lines), each block closed by INSTRUMENTATION_STATUS_CODE:
# 1 started, 0 passed, -1 error, -2 failed, -3 ignored, -4 assumption failed.
results = {}
block, key = {}, None
for line in text.splitlines():
    if line.startswith("INSTRUMENTATION_STATUS: "):
        k, _, v = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
        block[k], key = v, k
    elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
        code = int(line.split(":", 1)[1].strip() or 99)
        c, t = block.get("class"), block.get("test")
        if c and t:
            results[(c, t)] = (code, block.get("stack", ""))
        block, key = {}, None
    elif key == "stack":
        block["stack"] += "\n" + line

CLS = "uk.ccez.launcher.DrawerSwipeCloseTest"
TESTS = [
    ("swipeDownRightAfterOpeningClosesTheDrawer",
     "a swipe down within 300 ms of the swipe up that opened the drawer, during its open animation, closes it"),
    ("swipeDownAfterTheDrawerHasSettledClosesIt",
     "a swipe down after the open animation has settled, list at its top, closes the drawer"),
    ("swipeDownAfterScrollingBackToTheTopClosesTheDrawer",
     "scrolled back to the top by a swipe, the next swipe down closes the drawer"),
    ("swipeDownWithTheListScrolledPartwayOnlyScrolls",
     "with the list scrolled partway, a swipe down only scrolls it"),
    ("aSwipeDownThatReachesTheTopFromBelowStaysOpen",
     "a swipe down that starts below the top and carries the list there leaves the drawer open"),
    ("swipeUpInTheDrawerStillScrollsTheList",
     "a swipe up in the drawer still scrolls the list"),
    ("swipeRightInTheDrawerStillOpensTheHiddenSpace",
     "a swipe right in the drawer still opens the hidden space"),
]
KIND = {1: "didn't finish", -1: "error", -2: "failed", -3: "ignored", -4: "skipped (assumption failed)"}

fails = 0
for name, what in TESTS:
    r = results.get((CLS, name))
    if r is not None and r[0] == 0:
        print(f"PASS  {name}: {what}")
        continue
    fails += 1
    kind = "didn't run" if r is None else KIND.get(r[0], f"status {r[0]}")
    print(f"FAIL  {name}: {kind}: {what}")
    if r and r[1]:
        lines = [l for l in r[1].splitlines() if l.strip()]
        shown = lines[:1] + [l for l in lines[1:] if "DrawerSwipeCloseTest.kt" in l][:2]
        for l in shown:
            print("      " + l.strip()[:300])

# Known failures on main: "class#test File.kt:line" (known-failures.txt).
known = {}
for line in open(known_path):
    line = line.strip()
    if line and not line.startswith("#"):
        test, where = line.split()
        known[test] = where

def fails_at(c, stack):
    """The first line of the stack in the test's own file, as File.kt:line."""
    own = c.rsplit(".", 1)[-1] + ".kt:"
    for l in stack.splitlines():
        m = re.search(r"\((" + re.escape(own) + r"\d+)\)", l)
        if m:
            return m.group(1)
    return "?"

m = re.search(r"^OK \((\d+) tests?\)", text, re.M)
run = re.search(r"^Tests run: (\d+),\s+Failures: (\d+)", text, re.M)
total = int(m.group(1)) if m else (int(run.group(1)) if run else 0)
bad, excused = [], []
for (c, t), (code, stack) in sorted(results.items()):
    # Skipped by their own assumptions (-4) or ignored (-3): as the repo's
    # own run counts them. This gate's tests are held to a pass above.
    if code in (0, 1, -3, -4) or c == CLS:
        continue
    where = fails_at(c, stack)
    first = next((l.strip() for l in stack.splitlines() if l.strip()), "")
    line = f"{KIND.get(code, code)}: {c.rsplit('.', 1)[-1]}.{t} at {where}: {first[:200]}"
    if code == -2 and known.get(f"{c}#{t}") == where:
        excused.append(line)
    else:
        bad.append(line)
if not text or total < ui_min:
    fails += 1
    print(f"FAIL  every other UI test passes: {total} tests ran, fewer than {ui_min}")
elif bad:
    fails += 1
    print(f"FAIL  every other UI test passes: {len(bad)} failed")
    for l in bad:
        print("      " + l)
else:
    note = f", {len(excused)} failing where they fail on main too (known-failures.txt)" if excused else ""
    print(f"PASS  every other UI test passes ({total} tests ran{note})")
    for l in excused:
        print("      " + l)
sys.exit(1 if fails else 0)
PY
[ $? -eq 0 ] || FAILS=$((FAILS + 1))

if [ "$FAILS" -eq 0 ]; then
  echo "GATE PASS"
  exit 0
fi
echo "GATE FAIL"
exit 1
