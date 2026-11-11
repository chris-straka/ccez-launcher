# ccez-launcher

An Android home screen built around learning languages (see `PLAN.md`).

- Home, drawer (swipe up), hidden space (swipe right in the drawer),
  search that also does sums and conversions.
- Word logic is the ccez-keys Rust core (`~/SWE/ccez-keys/core`, UniFFI),
  built read-only from the sibling checkout; nothing word-shaped is
  re-written in Kotlin.
- Agents run it over adb: `bun scripts/launcher.ts status|get|set|log|dump|backup|restore`.
- Build and release locally (private repo, no Actions):
  `scripts/build.sh`, `scripts/release.sh`. Gates: `scripts/check.sh`
  (needs `LAUNCHER_SERIAL`).
