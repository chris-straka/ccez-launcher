package uk.ccez.launcher

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The agent commands end to end: through Control (as `launcher.ts` calls
 * it over adb), the layout file, and the home screen on display.
 */
@RunWith(AndroidJUnit4::class)
class AgentCommandsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var saved: LayoutData

    companion object {
        @JvmStatic @org.junit.BeforeClass fun deviceReady() {
            val ui = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            ui.executeShellCommand("cmd role add-role-holder android.app.role.HOME uk.ccez.launcher").close()
            ui.executeShellCommand("pm grant uk.ccez.launcher android.permission.READ_CONTACTS").close()
            App.instance.settings.set("words.app_name_langs", "false")
            App.instance.settings.set("words.clock_lang", "false")
        }
    }

    @Before fun keepLayout() {
        rule.waitUntil(timeoutMillis = 10_000) { App.instance.apps.loaded.value }
        saved = LayoutData.parse(App.instance.layout.data.value.toJson())
    }

    @After fun putLayoutBack() {
        App.instance.layout.replace(saved)
    }

    /** One Control call, as adb makes it: (out, error), decoded. */
    private fun call(method: String, args: JSONObject = JSONObject()): Pair<String?, String?> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val extras = Bundle().apply {
            putString("value", Base64.encodeToString(args.toString().toByteArray(), Base64.NO_WRAP))
        }
        val b = ctx.contentResolver.call(Uri.parse("content://uk.ccez.launcher.control"), method, null, extras)!!
        fun decoded(k: String) = b.getString(k)?.let { String(Base64.decode(it, Base64.DEFAULT)) }
        return decoded("out") to decoded("error")
    }

    private fun ok(method: String, args: JSONObject = JSONObject()): String {
        val (out, err) = call(method, args)
        assertNull("$method refused: $err", err)
        return out!!
    }

    /** Two installed apps neither pinned nor hidden, with labels unique on this device. */
    private fun twoFreeApps(): List<AppEntry> {
        val d = App.instance.layout.data.value
        val all = App.instance.apps.apps.value
        val free = all.filter { e ->
            e.key !in d.hidden && LayoutOps.rowIndexOf(d, e.id) < 0 && LayoutOps.folderOf(d, e.id) == null &&
                all.count { normalized(it.label) == normalized(e.label) } == 1
        }
        org.junit.Assume.assumeTrue("needs two unpinned apps", free.size >= 2)
        return free.take(2)
    }

    @Test fun pinMoveHideRedrawHomeAtOnce() {
        val (a, _) = twoFreeApps()
        assertTrue(ok("pin", JSONObject().put("app", a.label.lowercase())).startsWith("pinned ${a.label}"))
        rule.onNodeWithTag("app:${a.key}").assertIsDisplayed()

        ok("move", JSONObject().put("pin", a.label).put("to", 0))
        assertEquals(Pin.App(a.id), App.instance.layout.data.value.pins[0])
        val pins = JSONArray(ok("pins"))
        assertEquals(a.key, pins.getJSONObject(0).getString("key"))

        ok("hide", JSONObject().put("app", a.key))
        rule.waitUntil(timeoutMillis = 5_000) { rule.onAllNodesWithTag("app:${a.key}").fetchSemanticsNodes().isEmpty() }
        assertTrue(a.key in App.instance.layout.data.value.hidden)
        val listed = JSONArray(ok("apps", JSONObject().put("q", a.label)))
        val row = (0 until listed.length()).map { listed.getJSONObject(it) }.first { it.getString("key") == a.key }
        assertTrue(row.getBoolean("hidden"))
        assertTrue(row.isNull("pinned"))

        ok("unhide", JSONObject().put("app", a.label))
        assertFalse(a.key in App.instance.layout.data.value.hidden)
        // The command left its trace for agents.
        assertTrue(App.instance.launcherLog.tail(10).contains("\"event\":\"agent\""))
    }

    @Test fun badCommandsAreRefusedAndChangeNothing() {
        val before = App.instance.layout.data.value.toJson()
        val (_, unknown) = call("pin", JSONObject().put("app", "no app is called this"))
        assertNotNull(unknown)
        assertTrue(unknown!!.startsWith("no app matches"))
        // A prefix two labels share lists both instead of picking one.
        val labels = App.instance.apps.apps.value.map { normalized(it.label) }
        val shared = labels.map { it.take(1) }.groupingBy { it }.eachCount().filter { it.value >= 2 }.keys.first()
        val (_, many) = call("pin", JSONObject().put("app", shared))
        assertTrue(many, many!!.contains("say which"))
        val (_, range) = call("move", JSONObject().put("pin", "0").put("to", 99))
        assertNotNull(range)
        assertEquals(before, App.instance.layout.data.value.toJson())
    }

    @Test fun folderRenameUnfolderOnHome() {
        val (a, b) = twoFreeApps()
        val made = ok("folder", JSONObject().put("name", "Agent test").put("apps", JSONArray().put(a.key).put(b.key)))
        assertTrue(made, made.startsWith("made folder Agent test"))
        val folder = LayoutOps.findFolder(App.instance.layout.data.value, "agent test")
        rule.onNodeWithTag("folderpin:${folder.id}").assertIsDisplayed()
        ok("rename-folder", JSONObject().put("old", "agent").put("new", "Agent two"))
        assertEquals("Agent two", App.instance.layout.data.value.folders[folder.id]?.name)
        ok("unfolder", JSONObject().put("name", "Agent two"))
        rule.waitUntil(timeoutMillis = 5_000) { rule.onAllNodesWithTag("folderpin:${folder.id}").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("app:${a.key}").assertIsDisplayed()
    }

    @Test fun drawerAndHomeCommandsSwitchTheScreen() {
        ok("drawer")
        rule.waitUntil(timeoutMillis = 5_000) { rule.onAllNodesWithTag("drawer").fetchSemanticsNodes().isNotEmpty() }
        ok("home")
        rule.waitUntil(timeoutMillis = 5_000) { rule.onAllNodesWithTag("drawer").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("home").assertIsDisplayed()
    }
}
