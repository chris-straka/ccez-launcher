package uk.ccez.launcher

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The layout file: a bad or empty one falls back to the save before it, never to an empty dock. */
class LayoutStoreTest {
    private val horloge = Pin.App(AppId("fr.example.horloge", ".Horloge", 0))
    private val karte = Pin.App(AppId("de.example.karte", ".Karte", 0))

    private fun dir(): File = Files.createTempDirectory("layout").toFile()

    @Test fun aFirstRunIsFresh() {
        val d = dir()
        val store = LayoutStore(d, LauncherLog(d))
        assertTrue(store.fresh)
        assertTrue(store.data.value.pins.isEmpty())
    }

    @Test fun anEmptiedFileFallsBackToTheSaveBefore() {
        val d = dir()
        val store = LayoutStore(d, LauncherLog(d))
        store.update { it.pins.add(horloge) }
        store.flush()
        store.update { it.pins.add(karte) }
        store.flush()
        // A power cut left the file empty.
        File(d, "layout.json").writeText("")
        val again = LayoutStore(d, LauncherLog(d))
        assertFalse(again.fresh)
        // The backup is the save before the last: Horloge, not an empty dock.
        assertEquals(listOf<Pin>(horloge), again.data.value.pins)
        // The bad file is kept under its own name.
        assertTrue(d.listFiles()!!.any { it.name.startsWith("layout.json.bad-") })
    }

    @Test fun nothingToFallBackToSeedsAgain() {
        val d = dir()
        File(d, "layout.json").writeText("{ pas du json")
        val store = LayoutStore(d, LauncherLog(d))
        assertTrue(store.fresh)
        assertTrue(store.data.value.pins.isEmpty())
    }

    @Test fun savesRoundTrip() {
        val d = dir()
        val store = LayoutStore(d, LauncherLog(d))
        store.update { it.pins.addAll(listOf(horloge, karte)) }
        store.flush()
        assertEquals(listOf<Pin>(horloge, karte), LayoutStore(d, LauncherLog(d)).data.value.pins)
        assertFalse(File(d, "layout.json.tmp").exists())
    }

    @Test fun aChangeShowsAtOnceAndTheLastOneIsSaved() {
        val d = dir()
        val store = LayoutStore(d, LauncherLog(d))
        // Many quick changes (drags) on the caller's thread: each is on
        // screen at once, and the disk ends with the last.
        val apps = (1..40).map { Pin.App(AppId("ja.example.app$it", ".Main", 0)) }
        for (p in apps) {
            store.update { it.pins.add(p) }
            assertEquals(p, store.data.value.pins.last())
        }
        store.update { it.pins.removeAt(0) }
        store.flush()
        assertEquals(apps.drop(1), LayoutStore(d, LauncherLog(d)).data.value.pins)
    }

    @Test fun aFailedSaveIsReportedByFlush() {
        val d = dir()
        val store = LayoutStore(d, LauncherLog(d))
        // The folder is gone and a file sits where it was: nothing can be written.
        d.deleteRecursively()
        d.writeText("")
        store.update { it.pins.add(horloge) }
        assertEquals(listOf<Pin>(horloge), store.data.value.pins)
        assertTrue(runCatching { store.flush() }.isFailure)
        d.delete()
    }
}
