package uk.ccez.launcher

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The owner, 2026-11-26: "if I open the app drawer by sliding up, then
 * sliding down shortly after should close it instead of scrolling to the
 * top which I already am usually if I just opened the app drawer."
 *
 * A swipe down on the drawer's list closes the drawer whenever the list is
 * at its top when the finger lands: right after the drawer opens (its open
 * animation still running) and after it has settled. With the list scrolled
 * down, a swipe down only scrolls, even one that carries the list back to
 * its top. Swipes up and sideways stay the list's and the pager's.
 * Gate: gates/drawer-swipe-close/gate.sh.
 */
@RunWith(AndroidJUnit4::class)
class DrawerSwipeCloseTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private var layoutBefore: LayoutData? = null
    private var padded = false
    private val padScope = CoroutineScope(Dispatchers.Default)

    @Before fun setUp() {
        shell("cmd role add-role-holder android.app.role.HOME uk.ccez.launcher")
        shell("pm grant uk.ccez.launcher android.permission.READ_CONTACTS")
        App.instance.settings.set("words.app_name_langs", "false")
        App.instance.settings.set("words.clock_lang", "false")
        layoutBefore = LayoutData.parse(App.instance.layout.data.value.toJson())
        rule.waitUntil(timeoutMillis = 15_000) { App.instance.apps.apps.value.isNotEmpty() }
        rule.waitForIdle()
    }

    @After fun tearDown() {
        rule.mainClock.autoAdvance = true
        layoutBefore?.let { App.instance.layout.replace(it) }
        padScope.cancel()
        if (padded) {
            // The phone's own list back before the next test.
            App.instance.apps.refresh()
            val until = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < until && App.instance.apps.apps.value.any { PAD in it.id.cls }) {
                Thread.sleep(50)
            }
        }
    }

    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
    }

    private fun drawerOpen() = rule.onAllNodesWithTag("drawer").fetchSemanticsNodes().isNotEmpty()

    private fun homeShown() = rule.onAllNodesWithTag("home").fetchSemanticsNodes().isNotEmpty()

    /** Times home's swipe down asked for the notifications shade, in the log's recent lines. */
    private fun shadeAsks() = App.instance.launcherLog.tail(400).lines().count { "\"event\":\"shade" in it }

    /** The drawer's A–Z list (the grid that scrolls). */
    private fun list(): SemanticsNodeInteraction =
        rule.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("drawer-grid")))

    /** How far down the list is: 0 at its top. */
    private fun listPosition(): Float =
        list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    /**
     * The emulator has too few apps for the list to scroll: the same apps
     * again under other names until it does (the drawer shows the list as
     * it is; tearDown lists the real apps again). A fresh emulator's
     * package events list the apps again now and then: the padding goes
     * back on each new list, and on a quiet list to begin with.
     */
    private fun padTheList() {
        val repo = App.instance.apps
        var last = repo.apps.value
        var quietSince = System.currentTimeMillis()
        val until = quietSince + 20_000
        while (System.currentTimeMillis() < until && System.currentTimeMillis() - quietSince < 3_000) {
            Thread.sleep(100)
            if (repo.apps.value !== last) {
                last = repo.apps.value
                quietSince = System.currentTimeMillis()
            }
        }
        padded = true
        padScope.launch {
            repo.apps.collect { list ->
                if (list.isNotEmpty() && list.none { PAD in it.id.cls }) {
                    repo.apps.value = list + (1..160).map { i ->
                        val e = list[i % list.size]
                        e.copy(id = e.id.copy(cls = e.id.cls + PAD + i), label = "${e.label} $i")
                    }
                }
            }
        }
        rule.waitUntil(timeoutMillis = 10_000) { repo.apps.value.count { PAD in it.id.cls } == 160 }
        rule.waitForIdle()
    }

    /** A real swipe up on home's empty middle, as the owner opens the drawer. */
    private fun swipeUpOnHome() {
        rule.onNodeWithTag("home-empty").performTouchInput { swipeUp(durationMillis = 200) }
    }

    private fun openSettled() {
        swipeUpOnHome()
        rule.waitForIdle()
        assertTrue("the swipe up opened the drawer", drawerOpen())
    }

    /** A swipe down over the middle of the list: a quick flick by default. */
    private fun swipeDownOnTheList(from: Float = 0.3f, to: Float = 0.7f, durationMillis: Long = 150) {
        rule.onNodeWithTag("drawer-grid").performTouchInput {
            swipe(Offset(centerX, top + height * from), Offset(centerX, top + height * to), durationMillis)
        }
    }

    /**
     * The test's clock drives Compose here, and its animations run at their
     * full length whatever the emulator's animation scale: stopped, it
     * holds the drawer mid-way through its open animation.
     */
    @Test fun swipeDownRightAfterOpeningClosesTheDrawer() {
        val shadeBefore = shadeAsks()
        rule.mainClock.autoAdvance = false
        swipeUpOnHome()
        // About 50 ms after the finger lifts, while the drawer still rises in.
        rule.mainClock.advanceTimeBy(50)
        assertTrue("the swipe up opened the drawer", drawerOpen())
        assertTrue("the drawer's open animation is still running (home is still fading out under it)", homeShown())
        val position = listPosition()
        // Down 50 ms after the swipe up ended, up 150 ms later: well inside 300 ms.
        swipeDownOnTheList(durationMillis = 150)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertFalse(
            "a swipe down that ended 200 ms after the swipe up, during the open animation, closes the drawer " +
                "(the list was at $position when it began)",
            drawerOpen(),
        )
        assertTrue("home is back", homeShown())
        assertEquals("the swipe down didn't also pull the notifications shade", shadeBefore, shadeAsks())
    }

    @Test fun swipeDownAfterTheDrawerHasSettledClosesIt() {
        openSettled()
        rule.mainClock.advanceTimeBy(1_000)
        assertFalse("the open animation has ended (home is gone)", homeShown())
        assertEquals("a drawer just opened has its list at the top", 0f, listPosition())
        val shadeBefore = shadeAsks()
        swipeDownOnTheList()
        rule.waitForIdle()
        assertFalse("a swipe down on the settled drawer, its list at the top, closes it", drawerOpen())
        assertTrue("home is back", homeShown())
        assertEquals("the swipe down didn't also pull the notifications shade", shadeBefore, shadeAsks())
    }

    @Test fun swipeDownWithTheListScrolledPartwayOnlyScrolls() {
        padTheList()
        openSettled()
        list().performScrollToIndex(100)
        rule.waitForIdle()
        val before = listPosition()
        assertTrue("the list is scrolled down ($before)", before > 0f)
        // A slow, short pull: the list moves up some rows, nowhere near its top.
        swipeDownOnTheList(from = 0.3f, to = 0.55f, durationMillis = 600)
        rule.waitForIdle()
        val after = listPosition()
        assertTrue("the drawer stays open while its list is scrolled down", drawerOpen())
        assertTrue("the swipe scrolled the list toward its top ($before to $after)", after < before)
        assertTrue("the list isn't at its top yet ($after)", after > 0f)
    }

    @Test fun aSwipeDownThatReachesTheTopFromBelowStaysOpen() {
        padTheList()
        openSettled()
        list().performScrollToIndex(10)
        rule.waitForIdle()
        val before = listPosition()
        assertTrue("the list is scrolled down a little ($before)", before > 0f)
        // A quick flick from there carries the list to its top.
        swipeDownOnTheList()
        rule.waitForIdle()
        assertEquals("the swipe carried the list to its top", 0f, listPosition())
        assertTrue("a swipe that began with the list scrolled down only scrolls: the drawer stays", drawerOpen())
    }

    @Test fun swipeDownAfterScrollingBackToTheTopClosesTheDrawer() {
        padTheList()
        openSettled()
        list().performScrollToIndex(10)
        rule.waitForIdle()
        // Back to the top by a swipe of its own, as the owner would.
        swipeDownOnTheList()
        rule.waitForIdle()
        assertEquals("the list is back at its top", 0f, listPosition())
        assertTrue("the swipe that brought it there only scrolled", drawerOpen())
        val shadeBefore = shadeAsks()
        swipeDownOnTheList()
        rule.waitForIdle()
        assertFalse("with the list back at its top, the next swipe down closes the drawer", drawerOpen())
        assertTrue("home is back", homeShown())
        assertEquals("the swipe down didn't also pull the notifications shade", shadeBefore, shadeAsks())
    }

    @Test fun swipeUpInTheDrawerStillScrollsTheList() {
        padTheList()
        openSettled()
        assertEquals(0f, listPosition())
        rule.onNodeWithTag("drawer-grid").performTouchInput {
            swipe(Offset(centerX, top + height * 0.7f), Offset(centerX, top + height * 0.3f), 300)
        }
        rule.waitForIdle()
        assertTrue("the drawer stays open on a swipe up", drawerOpen())
        assertTrue("a swipe up scrolls the list down (${listPosition()})", listPosition() > 0f)
    }

    @Test fun swipeRightInTheDrawerStillOpensTheHiddenSpace() {
        openSettled()
        rule.onNodeWithTag("drawer-grid").performTouchInput { swipeRight() }
        rule.waitForIdle()
        rule.onNodeWithTag("hidden").assertIsDisplayed()
        assertFalse("a swipe right in the drawer doesn't go home", homeShown())
    }

    private companion object {
        /** Marks the padding entries' class names (never a real class). */
        const val PAD = "#drawer-swipe-pad"
    }
}
