package uk.ccez.launcher

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import uk.ccez.keys.core.ScreenWord

/** The review of 2026-11-14: a moved pin's cell, the gate over home, the server field. */
@RunWith(AndroidJUnit4::class)
class ReviewFixesTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic @org.junit.BeforeClass fun deviceReady() {
            val ui = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            ui.executeShellCommand("cmd role add-role-holder android.app.role.HOME uk.ccez.launcher").close()
            App.instance.settings.set("words.app_name_langs", "false")
        }
    }

    /** Two of the phone's own apps, told apart by package and by name, never the launcher itself. */
    private fun twoApps(): List<AppEntry> {
        rule.waitUntil(timeoutMillis = 10_000) { App.instance.apps.apps.value.size >= 3 }
        val picked = mutableListOf<AppEntry>()
        for (e in App.instance.apps.apps.value) {
            if (e.otherProfile || e.id.pkg == "uk.ccez.launcher" || e.label.isBlank()) continue
            if (picked.any { it.id.pkg == e.id.pkg || it.label == e.label }) continue
            picked.add(e)
            if (picked.size == 2) break
        }
        assertEquals("two apps to pin", 2, picked.size)
        return picked
    }

    /** Pins [a, b], then moves a after b without the row changing size (as
     * `launcher move` does): the cells keep the gesture handlers they
     * started with, so a stale one would still act on a in cell 0. */
    private fun pinThenMove(a: AppEntry, b: AppEntry) {
        App.instance.layout.update { d ->
            d.pins.clear()
            d.folders.clear()
            d.gates.remove(a.key)
            d.gates.remove(b.key)
            d.pins.addAll(listOf(Pin.App(a.id), Pin.App(b.id)))
        }
        rule.waitForIdle()
        App.instance.layout.update { d -> LayoutOps.move(d, 0, 1) }
        rule.waitForIdle()
        assertEquals(listOf<Pin>(Pin.App(b.id), Pin.App(a.id)), App.instance.layout.data.value.pins)
    }

    private fun inMenu(label: String) = hasText(label) and hasAnyAncestor(isDialog())

    @Test fun aHeldCellAfterAMoveActsOnTheAppItShows() {
        val (a, b) = twoApps()
        val saved = App.instance.layout.data.value.copyDeep()
        try {
            pinThenMove(a, b)
            // Cell 0 shows b now: its hold opens b's menu, titled b, not a's.
            rule.onNodeWithTag("app:${b.key}").performTouchInput { longClick() }
            rule.onNode(inMenu(b.label)).assertIsDisplayed()
            rule.onAllNodes(inMenu(a.label)).assertCountEquals(0)
            // And Unpin there takes b off, leaving a.
            rule.onNodeWithTag("menu-unpin").performClick()
            rule.waitForIdle()
            assertEquals(listOf<Pin>(Pin.App(a.id)), App.instance.layout.data.value.pins)
        } finally {
            App.instance.layout.replace(saved)
        }
    }

    @Test fun aTappedCellAfterAMoveLaunchesTheAppItShows() {
        val (a, b) = twoApps()
        val saved = App.instance.layout.data.value.copyDeep()
        try {
            pinThenMove(a, b)
            val log = App.instance.launcherLog
            val before = log.tail(400).lines().count { "\"event\":\"launch\"" in it }
            rule.onNodeWithTag("app:${b.key}").performClick()
            rule.waitUntil(timeoutMillis = 5_000) {
                log.tail(400).lines().count { "\"event\":\"launch\"" in it } > before
            }
            val last = log.tail(400).lines().last { "\"event\":\"launch\"" in it }
            assertEquals("cell 0 opens b", b.id.pkg, org.json.JSONObject(last).getString("pkg"))
        } finally {
            App.instance.layout.replace(saved)
        }
    }

    @Test fun theServerFieldIsSavedWhenSettingsCloses() {
        val app = App.instance
        val old = app.settings.get("words.server") as String
        try {
            rule.onNodeWithTag("home-empty").performTouchInput { longClick() }
            rule.onNodeWithTag("settings").assertIsDisplayed()
            val field = hasSetTextAction() and hasAnyAncestor(hasTestTag("setting:words.server"))
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("setting:words.server"))
            rule.onNode(field).performClick()
            rule.onNode(field).performTextReplacement("http://exemple.fr:8448")
            // Still focused, no Done: the home button (a HOME intent to the
            // running launcher, onNewIntent) shows home and Settings goes.
            val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            ctx.startActivity(
                android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME)
                    .setClass(ctx, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            rule.waitUntil(timeoutMillis = 5_000) { rule.onAllNodesWithTag("settings").fetchSemanticsNodes().isEmpty() }
            assertEquals("http://exemple.fr:8448", app.settings.get("words.server"))
        } finally {
            app.settings.set("words.server", org.json.JSONObject.quote(old))
            app.settingsChanged()
        }
    }

    @Test fun touchesOnTheGateNeverReachHome() {
        val (a, _) = twoApps()
        val saved = App.instance.layout.data.value.copyDeep()
        try {
            App.instance.layout.update { d -> if (d.pins.none { it == Pin.App(a.id) }) d.pins.add(0, Pin.App(a.id)) }
            rule.waitForIdle()
            val launches = { App.instance.launcherLog.tail(400).lines().count { "\"event\":\"launch\"" in it } }
            val before = launches()
            rule.runOnUiThread {
                rule.activity.gateWord = GateWord(a, ScreenWord("fr", "libre", "libre", "free", emptyList()))
            }
            rule.onNodeWithTag("gate").assertIsDisplayed()
            // A tap where the pins sit under the gate, and a swipe up.
            val pins = rule.onNodeWithTag("pins").fetchSemanticsNode().boundsInRoot
            val gate = rule.onNodeWithTag("gate").fetchSemanticsNode().boundsInRoot
            rule.onNodeWithTag("gate").performTouchInput {
                click(Offset(pins.left - gate.left + 30f, pins.center.y - gate.top))
            }
            rule.onNodeWithTag("gate").performTouchInput { swipeUp() }
            rule.waitForIdle()
            rule.onNodeWithTag("gate").assertIsDisplayed()
            assertTrue("no drawer behind the gate", rule.onAllNodesWithTag("drawer").fetchSemanticsNodes().isEmpty())
            assertEquals("nothing launched", before, launches())
        } finally {
            rule.runOnUiThread { rule.activity.gateWord = null }
            App.instance.layout.replace(saved)
        }
    }
}
