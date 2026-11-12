package uk.ccez.launcher

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The owner's two reports of 2026-11-12: the drawer opened with search
 * focused and the keyboard up; a swipe up on the pinned row did nothing.
 */
@RunWith(AndroidJUnit4::class)
class HomeGesturesTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private var layoutBefore: LayoutData? = null
    private var imeWithKeyboardBefore = ""

    @Before fun setUp() {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.executeShellCommand("cmd role add-role-holder android.app.role.HOME uk.ccez.launcher").close()
        ui.executeShellCommand("pm grant uk.ccez.launcher android.permission.READ_CONTACTS").close()
        App.instance.settings.set("words.app_name_langs", "false")
        App.instance.settings.set("words.clock_lang", "false")
        // The AVD has a hardware keyboard, which keeps the soft keyboard
        // away whatever the app asks: let it show, as on the phone.
        imeWithKeyboardBefore = shell("settings get secure show_ime_with_hard_keyboard").trim()
        shell("settings put secure show_ime_with_hard_keyboard 1")
        layoutBefore = LayoutData.parse(App.instance.layout.data.value.toJson())
    }

    @After fun tearDown() {
        layoutBefore?.let { App.instance.layout.replace(it) }
        val v = imeWithKeyboardBefore.takeIf { it == "0" || it == "1" } ?: "0"
        shell("settings put secure show_ime_with_hard_keyboard $v")
    }

    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
    }

    private fun imeShown(): Boolean {
        var shown = false
        rule.runOnIdle {
            val root = rule.activity.window.decorView
            shown = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return shown
    }

    private fun drawerOpen() = rule.onAllNodesWithTag("drawer").fetchSemanticsNodes().isNotEmpty()

    /** Two app pins at least, seeded on first run; first two returned. */
    private fun twoAppPins(): List<Pin.App> {
        val pins = App.instance.layout.data.value.pins.filterIsInstance<Pin.App>()
        assumeTrue("needs two seeded pins", pins.size >= 2)
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithTag("app:${pins[0].id.key()}").fetchSemanticsNodes().isNotEmpty()
        }
        return pins
    }

    @Test fun drawerOpensWithSearchUnfocusedAndNoKeyboard() {
        rule.onNodeWithTag("home-empty").performTouchInput { swipeUp() }
        rule.onNodeWithTag("drawer").assertIsDisplayed()
        rule.onNodeWithTag("search").assertIsNotFocused()
        // Give a late focus or keyboard request time to land.
        Thread.sleep(1_500)
        rule.onNodeWithTag("search").assertIsNotFocused()
        assertFalse("no keyboard when the drawer opens", imeShown())
        // A tap on the field: focus and the keyboard.
        rule.onNodeWithTag("search").performClick()
        rule.onNodeWithTag("search").assertIsFocused()
        rule.waitUntil(timeoutMillis = 10_000) { imeShown() }
        // Typing still searches, Enter still launches from the field.
        rule.onNodeWithTag("search").performTextInput("2+2")
        rule.onNodeWithTag("result-calc").assertIsDisplayed()
    }

    @Test fun swipeUpOnAPinOpensTheDrawer() {
        val pins = twoAppPins()
        val before = App.instance.layout.data.value.pins.toList()
        rule.onNodeWithTag("app:${pins[0].id.key()}").performTouchInput {
            swipe(center, center - Offset(0f, 400f), durationMillis = 150)
        }
        rule.waitUntil(timeoutMillis = 5_000) { drawerOpen() }
        rule.onNodeWithTag("drawer").assertIsDisplayed()
        assertEquals("the swipe moved no pin", before, App.instance.layout.data.value.pins)
    }

    @Test fun swipeUpOnTheAskFieldOpensTheDrawer() {
        rule.onNodeWithTag("llm-ask").performTouchInput { swipeUp() }
        rule.waitUntil(timeoutMillis = 5_000) { drawerOpen() }
        assertFalse("the swipe didn't bring the keyboard", imeShown())
    }

    @Test fun aHeldPinDraggedUpStillLiftsAndDrops() {
        // A hold, then a drag that goes up first: the pin's lift, not the drawer.
        val pins = twoAppPins()
        val origin = rule.onNodeWithTag("pins").fetchSemanticsNode().boundsInRoot.topLeft
        val first = rule.onNodeWithTag("app:${pins[0].id.key()}").fetchSemanticsNode().boundsInRoot.center - origin
        val second = rule.onNodeWithTag("app:${pins[1].id.key()}").fetchSemanticsNode().boundsInRoot.center - origin
        rule.onNodeWithTag("pins").performTouchInput {
            down(first)
            advanceEventTime(800)
            moveTo(first - Offset(0f, 300f))
            advanceEventTime(150)
            moveTo(second)
            advanceEventTime(200)
            up()
        }
        rule.waitForIdle()
        assertFalse("no drawer after a hold", drawerOpen())
        assertTrue("dropped on a pin: a folder", App.instance.layout.data.value.pins.any { it is Pin.FolderRef })
    }

    @Test fun aHeldFolderDraggedToAnotherCellMoves() {
        twoAppPins()
        // A folder first in the row, from the first two pins.
        App.instance.layout.update { d ->
            d.folders["ftest"] = Folder("ftest", "", mutableListOf(d.pins[0], d.pins[1]))
            d.pins.removeAt(1)
            d.pins[0] = Pin.FolderRef("ftest")
        }
        val now = App.instance.layout.data.value.pins
        assumeTrue("needs a pin beside the folder", now.size >= 2)
        val target = now.lastIndex
        val targetTag = when (val p = now[target]) {
            is Pin.App -> "app:${p.id.key()}"
            is Pin.Shortcut -> "shortcut:${p.pkg}/${p.shortcutId}"
            is Pin.FolderRef -> "folderpin:${p.folderId}"
        }
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithTag("folderpin:ftest").fetchSemanticsNodes().isNotEmpty()
        }
        val origin = rule.onNodeWithTag("pins").fetchSemanticsNode().boundsInRoot.topLeft
        val from = rule.onNodeWithTag("folderpin:ftest").fetchSemanticsNode().boundsInRoot.center - origin
        val to = rule.onNodeWithTag(targetTag).fetchSemanticsNode().boundsInRoot.center - origin
        rule.onNodeWithTag("pins").performTouchInput {
            down(from)
            advanceEventTime(800)
            moveTo(from + (to - from) / 2f)
            advanceEventTime(150)
            moveTo(to)
            advanceEventTime(200)
            up()
        }
        rule.waitForIdle()
        assertFalse("no drawer after a hold", drawerOpen())
        assertEquals(
            "the folder moved to the target cell",
            target,
            App.instance.layout.data.value.pins.indexOf(Pin.FolderRef("ftest")),
        )
    }
}
