#!/usr/bin/env bash
# A release on GitHub (private repo, built locally: no Actions): the
# signed APK and its SHA-256 (the launcher finds it there and installs
# it itself when updates.self is on, Updater.kt). Quality gates first:
# unit tests always; with LAUNCHER_SERIAL set, the perf gate too
# (scripts/perf.sh against perf-baseline.json).
#   scripts/release.sh
set -euo pipefail
cd "$(dirname "$0")/.."
[ -z "$(git status --porcelain)" ] || { echo "commit first" >&2; exit 1; }
./gradlew --console=plain -q :app:testDebugUnitTest
if [ -n "${LAUNCHER_SERIAL:-}" ]; then
  scripts/perf.sh
fi
NO_INSTALL=1 scripts/build.sh
CODE="$(git rev-list --count HEAD)"
TAG="v0.1.$CODE"
APK="app/build/outputs/apk/release/app-release.apk"
OUT="$(mktemp -d)"
cp "$APK" "$OUT/ccez-launcher-$TAG.apk"
(cd "$OUT" && shasum -a 256 "ccez-launcher-$TAG.apk" > "ccez-launcher-$TAG.apk.sha256")
if gh release view "$TAG" >/dev/null 2>&1; then echo "$TAG is released: commit something new first" >&2; exit 1; fi
# A tag left by a run that stopped before its release is used again.
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null || git tag "$TAG"
git push -q origin "$TAG"
gh release create "$TAG" "$OUT/ccez-launcher-$TAG.apk" "$OUT/ccez-launcher-$TAG.apk.sha256" \
  --title "ccez-launcher $TAG" --notes "$(git log -1 --format=%s)" --latest
echo "released $TAG"
