# ccez-launcher

An Android home screen (launcher) for the owner's Galaxy S24, built
around learning languages. It replaces Samsung's One UI Home. Owner,
2026-11-11: "I think I would like it better and it'd be snappier".

Read `~/AGENTS.md` (owner rules) and `~/SWE/AGENTS.md` first. This plan is
the spec; update it as you learn. Work the stages in order; each stage ends
with a release the owner can install.

## Ground rules

- Kotlin + Jetpack Compose, minSdk 26, targetSdk/compileSdk as
  `~/SWE/ccez-keys/android` uses (same Gradle and AGP versions: copy its
  `gradle/libs.versions.toml` and wrapper).
- Word data comes from the ccez-keys core (Rust, UniFFI), as the keyboard
  uses it: learning words, their statuses (shared with ccez-tube, ccez-llm,
  the Mac app through ccez-words), dictionaries and word cards. Reuse that
  crate (path or git dependency on `chris-straka/ccez-keys`, `core/`);
  never copy its logic into Kotlin.
- Speed is the point. Home shows instantly when an app closes: no
  recomposition storm, no work on the main thread at launch, the app list
  cached and refreshed in the background (LauncherApps callbacks). Measure
  cold start and return-to-home with `adb shell am start -W` and
  Macrobenchmark; put the numbers in this file.
- Quiet UI: pure black (AMOLED), no helper text, no branding, no badges
  (`~/SWE/AGENTS.md`, owner's taste notes). Sentence-case labels, few words.
- Verify on the emulator (`fleet run --emulator`, or the arm64 emulator on
  z: `~/Library/Android/sdk`), screenshots in `$FLEET_ARTIFACTS`. Never the
  owner's phone unless the owner asks that turn. Silent: emulator with
  `-no-audio`.
- Releases: private GitHub repo `chris-straka/ccez-launcher`; a signed APK
  per release (new keystore in `~/.config/ccez-launcher/`, backed up, never
  regenerated), self-update from GitHub releases as ccez-keys' keyboard does
  (`android/.../update/Updater.kt` there). No GitHub Actions (private repo,
  minutes are out): build and release locally with a `scripts/release.sh`.

## Stage 1: a launcher that's good at being a launcher — shipped

Shipped 2026-11-11 (see Status): HOME activity and the `ROLE_HOME` ask;
every profile's apps cached from `LauncherApps`; pinned row (drag to
reorder, drop on another for a folder, a hold dropped back opens the
menu); swipe-up drawer with search (apps, contacts, settings pages,
calculator, units; Enter launches the top app); app shortcuts on a hold
and pinned on home; hidden apps live only in the hidden space (swipe
right in the drawer, fingerprint when set; a Private space profile shows
there too, locked ones with an Unlock row); swipe down pulls the shade
(status-bar call, verified on the emulator); double-tap to lock through
a small accessibility service, only when turned on. Still open from this
stage: the S24 check (One UI gesture navigation and Recents with a
third-party launcher) — that needs the owner's phone. Widgets stay
Stage 6.

## Stage 2: learning words on the home screen — shipped

Shipped 2026-11-11: words in the empty part of home from the core's
`screen_words` (gloss rules and FSRS already in the core; the launcher
calls `screenWords`/`gradeScreenWord` as they are). Faint, in over 2 s,
out after 90 s (never under a finger — a held word shows its meaning);
tap Good, hold Again, double-tap Easy; grades sync through ccez-words
(`words.server` setting; `launcher sync`); dictionaries download per
language from the ccez-lang-core release, checked by SHA-256.
Settings: `words.count` (0–12) and `words.langs`, mirrored into the
core. Verified on launcher_avd with a live French dictionary
("parvenir à" → "to reach").

## Stage 3: search is also a dictionary — shipped

Shipped 2026-11-11: search also asks the core's `word_card` (learning
languages in the owner's order, first hit): headword as the lemma with
the typed form under it, article, gender, reading, parts of speech,
meanings with More for the rest, Learning / Known toggles by lemma,
synced like the keyboard's card. A language without its dictionary yet
simply shows no card (`launcher sync <lang>` fetches it).

## Stage 4: a word before a time-sink app — shipped

Shipped 2026-11-11: per-app gates (hold an app → "Word first"), off by
default. Opening a gated app first shows one due word — Again / Good /
Easy (or the app's name to open ungraded, or a second tap on the icon
straight through) — then the app opens. A slow word fetch never holds
the launch up (800 ms at most). Needs home words on (`words.count`),
since both come from the core's one screen-words call.

## Stage 5: the launcher speaks the languages — shipped

Shipped 2026-11-11 (`words.app_name_langs`, `words.clock_lang`): app
names translated once with ML Kit and cached (`app-names.json`),
rotating daily through the learning languages; a hold's menu keeps the
usual name, and search matches either. The clock and date written out
in the day's language; a tap says them (phone TTS, best voice, never
started in tests). Numbers 0–59 are spelled with small tables for 14
languages (ICU spellout is not in the public SDK and reflection to it
is blocked — checked on the emulator); other languages keep digits,
their weekday and month still localized.

## Stage 6: strips and widgets

- Continue strips: the last ccez-tube video at its place (deep link), a
  ccez-llm ask field (deep link with the text).
- Today: words reviewed, minutes listened (from ccez-tube), quiet numbers.
- The latest copy (clipboard, readable while home is focused) translated,
  one tap to save it as a learning word.
- Widget hosting for the few widgets the owner keeps.

## Built in from the start (future-proofing, owner agreed 2026-11-11)

- Run by agents (owner, 2026-11-08: "settings yourself", "logs for
  agents"): every setting in one JSON file the app watches, and an adb
  route to read and set them (`adb shell content call` on an exported,
  signature-or-shell-only ContentProvider, or a debug broadcast), wrapped
  by `scripts/launcher.ts get|set|log|dump`. A log with context (what was
  tapped, which app, timings) readable the same way; user-facing errors
  stay a few plain words.
- Backup and restore of the layout: pins, hidden apps, hidden space,
  folders, settings, as one JSON file, with `scripts/launcher.ts backup|
  restore` over adb (validated before writing), as ccez-keys' keyboard
  does (`Backup.kt`, `keys backup/restore`). Restores tested on the
  emulator, never the owner's phone.
- Quiet with the overlay: while ccez-launcher is the home app, the
  keyboard's words-on-screen overlay leaves the home screen to it (a
  shared setting or a broadcast; the overlay already reads the front app).
- One review schedule: the words use the core's FSRS scheduler (being added
  to ccez-keys' core, synced through ccez-words), never a schedule of their
  own.
- Quality gates, run before every release: unit tests, Compose UI tests
  on the emulator, screenshot tests of home, drawer, search, hidden space
  and a word card (kept under `screenshots/`, diffs reviewed), and a
  Macrobenchmark of cold start and return-to-home that fails the release
  when either gets slower than the numbers recorded here.
- Big text and TalkBack: every screen works at the largest font and
  display size, words and labels readable by TalkBack.
- Tablet and landscape: layouts that use the width (more columns, the
  drawer beside home), checked on a tablet emulator.
- Edge-to-edge and predictive back from the start; targetSdk kept current.

## Not doing

- Icon packs, themes, a launcher settings maze. One look, chosen here.
- Anything that needs Google Play services.

## Status

Stage 1 shipped 2026-11-11 (first tagged release; one release per stage).
Everything under "Stage 1" above and "Built in from the start" is in:
settings in one watched JSON with `scripts/launcher.ts get|set|log|dump`
over adb (shell-only ContentProvider), layout backup/restore validated
before writing (tested round-trip and bad-backup rejection on the
emulator), self-update from GitHub releases (`updates.self`, off unless
set), edge-to-edge + predictive back, adaptive columns and a beside-home
drawer in wide landscape, TalkBack labels, large-font checked. Perf on
launcher_avd (arm64 emulator, software rendering, busy host — tripwire
numbers only): Macrobenchmark cold median 471 ms (min 388),
return-to-home median 180 ms (min 109); `am start -W` best 256 ms /
61 ms on a quiet run. Baseline in `perf-baseline.json` (700 / 150 ms,
gate at +25% in `scripts/perf.sh`); replace with S24 numbers on the
owner's install. Gates run 2026-11-11: unit tests, 10 instrumented
tests, screenshots in `screenshots/`, perf — all green on launcher_avd.
Notes: the overlay truce is launcher-side only (it broadcasts
`HOME_VISIBLE` to uk.ccez.keys and reports `home_visible` in status);
the keys overlay still needs its own front-app check for
uk.ccez.launcher, in that repo. Private space and the second profile
are coded but untested here (this emulator has neither). The S24
One UI/Recents check and the "delete the word widget" call wait on the
owner's install.

Stage 2 shipped 2026-11-11: home words with meanings and grades through
the core (see above). Stage 3 shipped the same day: word cards in
search. Stage 4 shipped the same day: word gates before chosen apps
(verified with YouTube on the emulator). Stage 5 shipped the same
day: language-of-the-day names and clock. Next: Stage 6.
