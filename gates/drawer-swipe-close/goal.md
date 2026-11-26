# Goal: a swipe down closes the drawer when its list is at the top

Repo: ccez-launcher, an Android home screen in Kotlin and Jetpack Compose.
The gate, `gates/drawer-swipe-close/gate.sh`, judges the work.

## The owner's ask (2026-11-26)

"if I open the app drawer by sliding up, then sliding down shortly after
should close it instead of scrolling to the top which I already am usually if
I just opened the app drawer."

So: right after the drawer opens, and whenever its list is already at the
top, a downward swipe closes the drawer. The list never treats it as a scroll
or a fling to the top. A downward swipe while the list is scrolled further
down still scrolls the list, as now.

## Why it scrolls today

- The drawer has no downward swipe of its own. `DrawerScreen` (in
  `app/src/main/kotlin/uk/ccez/launcher/DrawerScreen.kt`) closes only on
  Back, on launching an app or a search result. A swipe down on it lands on
  the A–Z list, `AppGrid`'s `LazyVerticalGrid` inside the `HorizontalPager`,
  and the grid's scrollable takes it as a scroll toward the top.
- Right after opening, the list is already at its top, during the open
  animation too: `AppGrid` makes a new `LazyGridState` each time the drawer
  opens (index 0, offset 0). No fling carries over from the opening swipe:
  home's `Modifier.homeSwipes` (`HomeScreen.kt`) takes that whole gesture
  and opens the drawer only when the finger lifts. The pager takes only
  sideways drags, and nothing else nests a scroll around the grid.
- So the swipe has nothing to scroll, and Android's overscroll stretch plays:
  the list stretches down and springs back, which reads as "scrolling to the
  top", and the drawer stays open.
- The gate on main shows it (see the run log below): the three closing tests
  fail with the drawer still open, the list at position 0 when each swipe
  began; the four tests of what must not change pass.

## What to build (follow this design)

1. In `DrawerScreen.kt`, a pointer modifier for the drawer's list in the
   style of `Modifier.homeSwipes`: `pointerInput` with `awaitEachGesture`,
   `awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)`,
   and every event read on the Initial pass, so it sees the drag before the
   grid's own scrollable does.
2. The grid's state is hoisted so the modifier can read it: `AppGrid` gains a
   `state: LazyGridState = rememberLazyGridState()` parameter that it passes
   to its `LazyVerticalGrid`; `DrawerPage` remembers one and passes it.
3. At the finger's down, the modifier asks whether the list is at its top
   (`!state.canScrollBackward`). Not at the top: it leaves the whole gesture
   alone, so the grid scrolls and flings as now, even when that scroll
   reaches the top.
4. At the top: once the finger has gone down past
   `viewConfiguration.touchSlop` and mostly vertically (`dy > 2 * |dx|`, as
   `homeSwipes` tells a vertical swipe), the modifier takes the gesture and
   consumes every change from then on, so the grid neither scrolls nor
   stretches. When the finger lifts, the drawer closes (`onClose`, the one
   `DrawerScreen` already gets) if the finger went down more than 70 px, the
   distance `homeSwipes` uses.
5. Before it takes the gesture, an upward or sideways move past the slop, a
   hold past `viewConfiguration.longPressTimeoutMillis`, or a change already
   consumed ends its watch: the gesture stays the grid's, the pager's or the
   app cell's (a scroll, the hidden space, a tap, a hold's menu).
6. Only the drawer page's list (search empty) gets it, through the modifier
   `DrawerPage` passes to `AppGrid`. The search results, the hidden space,
   home's swipes and the open and close animation (`screenMotion` in
   `MainActivity.kt`) stay as they are.

## What must hold (each is a test in `DrawerSwipeCloseTest`)

- `swipeDownRightAfterOpeningClosesTheDrawer`: a real swipe up on home, then
  a swipe down on the list that starts about 50 ms after the finger lifts and
  ends 200 ms after, while the open animation still runs: the drawer closes,
  home is back, and the notifications shade wasn't asked for.
- `swipeDownAfterTheDrawerHasSettledClosesIt`: the same after the open
  animation has ended.
- `swipeDownAfterScrollingBackToTheTopClosesTheDrawer`: with the list
  scrolled down, one swipe down brings it back to the top and leaves the
  drawer open; the next swipe down closes it.
- `swipeDownWithTheListScrolledPartwayOnlyScrolls`: scrolled far down, a
  slow short swipe down scrolls the list some rows and the drawer stays.
- `aSwipeDownThatReachesTheTopFromBelowStaysOpen`: scrolled two rows down, a
  quick swipe down carries the list to its top and the drawer stays.
- `swipeUpInTheDrawerStillScrollsTheList` and
  `swipeRightInTheDrawerStillOpensTheHiddenSpace`.
- Every other unit and UI test still passes.

## Rules

- Change only `app/src/main/kotlin/uk/ccez/launcher/DrawerScreen.kt`
  (`HomeScreen.kt` too only if you share code with `homeSwipes`, whose
  behaviour must stay exactly as it is). Nothing else under `app/src/main`.
- No timing constants: no delays, no time window after the drawer opens, no
  check of whether the open animation runs. Whether a swipe closes the drawer
  depends only on the list being at its top when the finger lands and on the
  distances above.
- Nothing aimed at the tests: no test tags, no padded entries' names, no
  instrumentation or "running in a test" checks in the app's code.
- Protected, do not edit, move or delete: `gates/`, `app/src/androidTest/`,
  `app/src/test/`, `scripts/`. Don't edit `PLAN.md`, `README.md` or
  `AGENTS.md`; the reviewer updates them.
- Builds and tests run only through the runner's `check` (fleet, with an
  emulator). Don't start Gradle, an emulator or adb yourself.

## Evidence

The gate prints `GATE PASS`. It runs `scripts/fleet-check.sh` as the repo
does (unit tests, build, every UI test on an emulator it boots), then prints
PASS or FAIL for: the app code doesn't look for the tests; unit tests; the
build; the UI tests ran to the end; each of the seven tests above by name;
every UI test passes (at least 39).

## Run log

- 2026-11-26: the gate needs an emulator, and fleet's only emulator machine
  runs Linux, so the gate runs with `--emulator` and its base check on Linux
  only.
