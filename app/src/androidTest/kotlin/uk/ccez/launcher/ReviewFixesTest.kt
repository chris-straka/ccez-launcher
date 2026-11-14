package uk.ccez.launcher

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import uk.ccez.keys.core.ScreenWord

/** The review of 2026-11-14: a moved pin's cell, and the gate over home. */
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

    private fun twoApps(): List<AppEntry> {
        rule.waitUntil(timeoutMillis = 10_000) { App.instance.apps.apps.value.size >= 2 }
        return App.instance.apps.apps.value.filter { !it.otherProfile }.take(2)
    }

    @Test fun aHeldCellAfterAMoveActsOnTheAppItShows() {
        val (a, b) = twoApps()
        val saved = App.instance.layout.data.value.copyDeep()
        try {
            App.instance.layout.update { d ->
                d.pins.clear()
                d.folders.clear()
                d.pins.addAll(listOf(Pin.App(a.id), Pin.App(b.id)))
            }
            rule.waitForIdle()
            // Moved without the row changing size (as `launcher move` does):
            // the cells' gesture handlers are the ones they started with.
            App.instance.layout.update { d -> LayoutOps.move(d, 0, 1) }
            rule.waitForIdle()
            // Cell 0 shows B now; its hold must open B's menu, not A's.
            rule.onNodeWithTag("app:${b.key}").performTouchInput { longClick() }
            rule.onNodeWithTag("menu-unpin").performClick()
            rule.waitForIdle()
            assertEquals(listOf<Pin>(Pin.App(a.id)), App.instance.layout.data.value.pins)
        } finally {
            App.instance.layout.replace(saved)
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
