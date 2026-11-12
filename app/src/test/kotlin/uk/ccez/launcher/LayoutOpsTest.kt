package uk.ccez.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The agent layout commands: matching names, and each change to the layout. */
class LayoutOpsTest {
    private val chrome = AppRef(AppId("com.android.chrome", "com.google.android.apps.chrome.Main", 0), "Chrome")
    private val camera = AppRef(AppId("com.android.camera2", "com.android.camera.CameraLauncher", 0), "Camera")
    private val calendar = AppRef(AppId("com.google.android.calendar", ".Launch", 0), "Calendar")
    private val clock = AppRef(AppId("com.google.android.deskclock", ".DeskClock", 0), "Clock")
    private val workChrome = AppRef(AppId("com.android.chrome", "com.google.android.apps.chrome.Main", 10), "Chrome", "work")
    private val reglages = AppRef(AppId("com.android.settings", ".Settings", 0), "Réglages")
    private val apps = listOf(chrome, camera, calendar, clock, reglages)
    private val labels = (apps + workChrome).associate { it.id to it.label }
    private val labelOf: (AppId) -> String? = { labels[it] }

    private fun refused(block: () -> Unit): String {
        try {
            block()
        } catch (e: AgentError) {
            return e.message.orEmpty()
        }
        fail("should have been refused")
        error("unreachable")
    }

    private fun row(vararg a: AppRef) = LayoutData(pins = a.map { Pin.App(it.id) as Pin }.toMutableList())

    private fun names(d: LayoutData) = d.pins.map { LayoutOps.pinLabel(d, it, labelOf) }

    @Test fun matchesByLabelPrefixKeyAndPackage() {
        assertEquals(chrome, LayoutOps.findApp(apps, "chrome"))
        assertEquals(chrome, LayoutOps.findApp(apps, "CHR"))
        assertEquals(clock, LayoutOps.findApp(apps, "clo"))
        assertEquals(camera, LayoutOps.findApp(apps, "com.android.camera2"))
        assertEquals(calendar, LayoutOps.findApp(apps, calendar.key))
        // Accents and case don't matter.
        assertEquals(reglages, LayoutOps.findApp(apps, "reglages"))
    }

    @Test fun anExactLabelBeatsALongerOne() {
        val cal = AppRef(AppId("a.cal", ".M", 0), "Cal")
        assertEquals(cal, LayoutOps.findApp(apps + cal, "cal"))
    }

    @Test fun ambiguousNamesListTheCandidates() {
        val said = refused { LayoutOps.findApp(apps, "ca") }
        assertTrue(said, said.contains("matches 2 apps"))
        assertTrue(said.contains(camera.key) && said.contains(calendar.key))
        // Same label in two profiles: listed with the profile, the key picks.
        val both = apps + workChrome
        val two = refused { LayoutOps.findApp(both, "Chrome") }
        assertTrue(two, two.contains("(work)"))
        assertEquals(workChrome, LayoutOps.findApp(both, workChrome.key))
    }

    @Test fun unknownNamesAreRefused() {
        assertTrue(refused { LayoutOps.findApp(apps, "nothing like it") }.startsWith("no app matches"))
        assertTrue(refused { LayoutOps.findApp(apps, "  ") }.startsWith("no app matches"))
    }

    @Test fun pinAppendsOrInsertsAndRefusesDuplicatesAndHidden() {
        val d = row(chrome, camera)
        assertEquals(2, LayoutOps.pin(d, clock))
        assertEquals(0, LayoutOps.pin(d, calendar, at = 0))
        assertEquals(listOf("Calendar", "Chrome", "Camera", "Clock"), names(d))
        assertTrue(refused { LayoutOps.pin(d, chrome) }.contains("already pinned at 1"))
        assertTrue(refused { LayoutOps.pin(d, reglages, at = 9) }.contains("out of range"))
        d.hidden.add(reglages.key)
        assertTrue(refused { LayoutOps.pin(d, reglages) }.contains("hidden"))
        assertEquals(4, d.pins.size)
    }

    @Test fun locateByPositionOrName() {
        val d = row(chrome, camera, clock)
        assertEquals(Spot.Row(1), LayoutOps.locate(d, "1", labelOf))
        assertEquals(Spot.Row(2), LayoutOps.locate(d, "clock", labelOf))
        assertTrue(refused { LayoutOps.locate(d, "7", labelOf) }.contains("out of range (0–2)"))
        assertTrue(refused { LayoutOps.locate(d, "c", labelOf) }.contains("matches 3 pins"))
        // Only what is pinned: Calendar is installed but not in the row.
        assertTrue(refused { LayoutOps.locate(d, "calendar", labelOf) }.startsWith("no pin matches"))
    }

    @Test fun moveReorders() {
        val d = row(chrome, camera, clock)
        LayoutOps.move(d, 0, 2)
        assertEquals(listOf("Camera", "Clock", "Chrome"), names(d))
        LayoutOps.move(d, 2, 0)
        assertEquals(listOf("Chrome", "Camera", "Clock"), names(d))
        refused { LayoutOps.move(d, 0, 3) }
        assertEquals(listOf("Chrome", "Camera", "Clock"), names(d))
    }

    @Test fun unpinFromTheRowOrAFolder() {
        val d = row(chrome, camera)
        LayoutOps.folder(d, "Time", listOf(clock, calendar))
        assertEquals(Pin.App(chrome.id), LayoutOps.unpin(d, LayoutOps.locate(d, "chrome", labelOf, inFolders = true)))
        // Clock is only in the folder.
        val spot = LayoutOps.locate(d, "clock", labelOf, inFolders = true)
        assertTrue(spot is Spot.InFolder)
        LayoutOps.unpin(d, spot)
        val f = d.folders.values.single()
        assertEquals(listOf<Pin>(Pin.App(calendar.id)), f.items)
        // The last one out takes the folder with it.
        LayoutOps.unpin(d, LayoutOps.locate(d, "calendar", labelOf, inFolders = true))
        assertTrue(d.folders.isEmpty())
        assertEquals(listOf("Camera"), names(d))
    }

    @Test fun hideTakesTheAppOffHomeEverywhere() {
        val d = row(chrome, camera)
        LayoutOps.folder(d, "Time", listOf(clock))
        LayoutOps.hide(d, chrome)
        LayoutOps.hide(d, clock)
        assertTrue(chrome.key in d.hidden && clock.key in d.hidden)
        assertEquals(listOf("Camera"), names(d))
        assertTrue(d.folders.isEmpty())
        assertTrue(refused { LayoutOps.hide(d, chrome) }.contains("already hidden"))
        LayoutOps.unhide(d, chrome.key, chrome.label)
        assertFalse(chrome.key in d.hidden)
        assertTrue(refused { LayoutOps.unhide(d, chrome.key, chrome.label) }.contains("isn't hidden"))
    }

    @Test fun folderTakesThePlaceOfItsFirstPinnedApp() {
        val d = row(chrome, camera, clock, calendar)
        val (f, made) = LayoutOps.folder(d, "Time", listOf(clock, calendar))
        assertTrue(made)
        assertEquals(listOf("Chrome", "Camera", "Time"), names(d))
        assertEquals(listOf<Pin>(Pin.App(clock.id), Pin.App(calendar.id)), f.items)
        // Same name (any case) adds to it, and pulls the app off the row.
        val (again, madeAgain) = LayoutOps.folder(d, "time", listOf(chrome, clock))
        assertFalse(madeAgain)
        assertEquals(f.id, again.id)
        assertEquals(3, again.items.size)
        assertEquals(listOf("Camera", "Time"), names(d))
        // Unpinned apps make a folder at the end.
        val (g, _) = LayoutOps.folder(d, "Look", listOf(reglages))
        assertEquals(Pin.FolderRef(g.id), d.pins.last())
        refused { LayoutOps.folder(d, " ", listOf(camera)) }
        refused { LayoutOps.folder(d, "Empty", emptyList()) }
    }

    @Test fun anAppMovesBetweenFolders() {
        val d = row(chrome, camera, clock)
        LayoutOps.folder(d, "A", listOf(chrome, camera))
        LayoutOps.folder(d, "B", listOf(camera, clock))
        val a = LayoutOps.findFolder(d, "A")
        val b = LayoutOps.findFolder(d, "B")
        assertEquals(listOf<Pin>(Pin.App(chrome.id)), a.items)
        assertEquals(listOf<Pin>(Pin.App(camera.id), Pin.App(clock.id)), b.items)
    }

    @Test fun unfolderPutsItemsBackInPlace() {
        val d = row(chrome, camera, clock)
        LayoutOps.folder(d, "Two", listOf(camera, clock))
        d.pins.add(Pin.App(calendar.id))
        LayoutOps.unfolder(d, LayoutOps.findFolder(d, "two"))
        assertEquals(listOf("Chrome", "Camera", "Clock", "Calendar"), names(d))
        assertTrue(d.folders.isEmpty())
    }

    @Test fun renameRefusesClashesAndBlanks() {
        val d = row(chrome, camera, clock)
        LayoutOps.folder(d, "Web", listOf(chrome))
        LayoutOps.folder(d, "Photo", listOf(camera))
        val web = LayoutOps.findFolder(d, "web")
        assertTrue(refused { LayoutOps.renameFolder(d, web, "photo") }.contains("already a folder"))
        refused { LayoutOps.renameFolder(d, web, "") }
        LayoutOps.renameFolder(d, web, "Browse")
        assertEquals("Browse", LayoutOps.findFolder(d, "brow").name)
        // A folder is also found by its id.
        assertEquals("Browse", LayoutOps.findFolder(d, web.id).name)
    }

    @Test fun gateTurnsOnAndOff() {
        val d = LayoutData()
        assertTrue(LayoutOps.gate(d, chrome.key, true))
        assertFalse(LayoutOps.gate(d, chrome.key, true))
        assertTrue(chrome.key in d.gates)
        assertTrue(LayoutOps.gate(d, chrome.key, false))
        assertTrue(d.gates.isEmpty())
    }

    @Test fun shortcutsPinOnce() {
        val d = row(chrome)
        val s = Pin.Shortcut(chrome.id.pkg, "new-tab", 0, "New tab")
        assertEquals(0, LayoutOps.pinShortcut(d, s, at = 0))
        assertEquals(Spot.Row(0), LayoutOps.locate(d, "new tab", labelOf))
        assertEquals(Spot.Row(0), LayoutOps.locate(d, "new-tab", labelOf))
        assertTrue(refused { LayoutOps.pinShortcut(d, s) }.contains("already pinned"))
    }
}
