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
menu); swipe up anywhere on home (over the pins, the strips and the Ask
field too; a hold is the pin's lift, never the drawer) opens the drawer
with search (apps, contacts, settings pages, calculator, units; Enter
launches the top app). Opening the drawer leaves search unfocused and
the keyboard down; a tap on the field brings both (owner, 2026-11-12); app shortcuts on a hold
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

## Stage 6: strips and widgets — shipped, two numbers blocked on ccez-tube

Shipped 2026-11-11: a Today strip (words reviewed here today), the
latest copy read while home is focused (dictionary meaning, one tap to
save as Learning, dismiss per copy), an Ask field through ccez-llm's
`ccez-llm://send?text=` deep link, and widget hosting (pick in Settings
or another app's pin request, configure when asked, hold to remove;
restores rebind by provider). Blocked, written here plainly: ccez-tube
exposes no cross-app state (no control provider, no custom scheme —
checked in `~/SWE/ccez-tube` 2026-11-11), so "the last video at its
place" ships as a Continue strip that opens ccez-tube (which resumes
itself), and "minutes listened" is not shown. Both need a small
read-only surface in ccez-tube (a `content call` like this app's
Control) — outside this repo. The Ask deep link is verified only to
the point of a clean "not installed" log on the emulator; ccez-llm
isn't installed there.

## Built in from the start (future-proofing, owner agreed 2026-11-11)

- Run by agents (owner, 2026-11-08: "settings yourself", "logs for
  agents"): every setting in one JSON file the app watches, and an adb
  route to read and set them (`adb shell content call` on an exported,
  signature-or-shell-only ContentProvider, or a debug broadcast), wrapped
  by `scripts/launcher.ts get|set|log|dump`. A log with context (what was
  tapped, which app, timings) readable the same way; user-facing errors
  stay a few plain words.
- The layout too, without tapping through the app (owner, 2026-11-12):
  `launcher apps [--json] [query]`, `pins`, `pin <app> [--at N]`,
  `unpin <app|N>`, `move <app|N> <to>`, `hide`/`unhide <app>`, `hidden`,
  `folder <name> <app>…`, `unfolder <name>`, `rename-folder <old> <new>`,
  `gate <app> on|off`, `open <app>`, `home`, `drawer`, `shortcuts <app>`,
  `pin-shortcut <app> <id|label>` (AgentCommands.kt, the pure part in
  LayoutOps.kt). Each is one call, checked before anything is written,
  logged as an `agent` event, and through `LayoutStore.update`, so the
  open screen redraws at once. `<app>` is a label (any case, a unique
  prefix), a package or a key; a name that fits several lists them and
  changes nothing; only installed apps pin. `open` skips the word gate.
- From any tailnet computer: adb is the route, no server. Wireless
  debugging on the phone, reached over the tailnet:
  `LAUNCHER_SERIAL=<phone tailnet address>:<port> bun scripts/launcher.ts …`
  runs `adb connect` first when needed. z is paired already; the address
  and port live outside this repo (the port changes when wireless
  debugging restarts). Another computer pairs once (`adb pair`, the code
  the phone shows) before connecting.
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

2026-11-12: agent commands for the layout (see "Run by agents"): apps,
pins, pin/unpin/move, hide/unhide/hidden, folders, gate, open, home,
drawer, shortcuts, pin-shortcut; `LAUNCHER_SERIAL` takes host:port for
wireless debugging over the tailnet. Unit tests for matching and every
layout change (LayoutOpsTest), instrumented tests through Control
(AgentCommandsTest). `open`, `home` and `drawer` start activities from the
provider; that works on launcher_avd (API 36, the home role held) with
another app in front — the S24 isn't checked.

2026-11-12, branch `look` (not merged, waiting on the owner's yes): one
refined look. One grid (20 dp margins; dock and drawer icons land on
them), a thin left-aligned clock, filled pill fields for Search and Ask,
six pins in one row on a phone (`dockColumns`), search results with app
icons and the kind on the right, quiet hairline-split app menu with the
app's icon, word cards with lighter chips, home words that wrap their
meaning on screen and keep apart (spacing follows the font size), and the
drawer rising in and sinking out. Behaviour and gestures unchanged; all
gates passed on launcher_avd (23 instrumented tests), goldens updated
except `home-widget.png` and `gate.png` (unchanged screens' code).

2026-11-12, from the owner's S24: the drawer no longer focuses search or
raises the keyboard on open, and a swipe up on the pinned row, the Ask
field or the Continue strip opens the drawer as on empty home (a hold
still lifts a pin). UI tests for both (`HomeGesturesTest`), all gates
passed on launcher_avd.

2026-11-11, after the build: rebuilt on ccez-keys 9f87bb3, so words are
saved and looked up by their dictionary headword (ccez-words/PLAN.md) and
cards show short form tags. Checked on launcher_avd by hand: drawer,
search, calculator, hidden space and the app menu are this launcher's (the
first screenshots were the emulator's own launcher: the connected tests
uninstall the app, scripts/check.sh now reinstalls it before any shot);
hiding an app leaves the open drawer at once; the clipboard is read once
per copy. Installed on the owner's S24 as the home app.

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
owner's install. Gates run per stage on launcher_avd (`scripts/check.sh`):
unit tests, instrumented tests, screenshots in `screenshots/`, perf.
Notes: the overlay truce is launcher-side only (it broadcasts
`HOME_VISIBLE` to uk.ccez.keys and reports `home_visible` in status);
the keys overlay still needs its own front-app check for
uk.ccez.launcher, in that repo. Private space and the second profile
are coded but untested here (this emulator has neither). The S24
One UI/Recents check and the "delete the word widget" call wait on the
owner's install.

Stages 2–6 shipped the same day (see each above), a tagged release
each. Gates: unit tests, 18 instrumented tests, screenshots, perf —
run per stage on launcher_avd. What remains is all owner-side or
another repo's: install a release on the S24 and fill in the One UI
gesture/Recents findings and real perf numbers; check the second
profile and Private space on that phone; the keys overlay's front-app
check (in ccez-keys); a read-only state surface in ccez-tube for the
last video's title and minutes listened; and hearing the clock speak
(TTS is never started in emulator tests). The signing key lives in
`~/.config/ccez-launcher/` (made once by `scripts/build.sh`) — back it
up; a new key means uninstalling the launcher and its layout.
