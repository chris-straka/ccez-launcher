#!/usr/bin/env bash
# A release on GitHub (public repo, built locally: no Actions): the
# signed APK and its SHA-256 (the launcher finds it there and installs
# it itself when updates.self is on, Updater.kt). Quality gates first:
# unit tests always; with LAUNCHER_SERIAL set, the perf gate too
# (scripts/perf.sh against perf-baseline.json).
#   scripts/release.sh
set -euo pipefail
cd "$(dirname "$0")/.."
[ -z "$(git status --porcelain)" ] || { echo "commit first" >&2; exit 1; }
# The word core is built from the ccez-keys checkout (app/build.gradle.kts):
# a release ships only committed core code, and says which commit. The
# build reads core/, the data it compiles in (include_str! of data/), and
# the workspace's Cargo.toml (release profile) and Cargo.lock.
KEYS="${CCEZ_KEYS_DIR:-../ccez-keys}"
CORE_PATHS=(core data Cargo.toml Cargo.lock)
DIRTY="$(git -C "$KEYS" status --porcelain -- "${CORE_PATHS[@]}")"
[ -z "$DIRTY" ] || { printf '%s has uncommitted changes the core builds from: commit them there, or point CCEZ_KEYS_DIR at a clean checkout\n%s\n' "$KEYS" "$DIRTY" >&2; exit 1; }
CORE="$(git -C "$KEYS" rev-parse --short HEAD)"
./gradlew --console=plain -q :app:testDebugUnitTest
if [ -n "${LAUNCHER_SERIAL:-}" ]; then
  scripts/perf.sh
else
  echo "no LAUNCHER_SERIAL: the perf gate is skipped" >&2
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
  --title "ccez-launcher $TAG" --notes "$(git log -1 --format=%s) (ccez-keys core $CORE)" --latest
echo "released $TAG"
