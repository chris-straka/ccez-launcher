package uk.ccez.launcher

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Layout and settings files: round trips and restore validation. */
class LayoutTest {
    private val app = AppId("com.example.a", ".Main", 0)
    private val app2 = AppId("com.example.b", ".Main", 10)

    @Test fun layoutRoundTrip() {
        val d = LayoutData(
            pins = mutableListOf(Pin.App(app), Pin.FolderRef("f1")),
            folders = mutableMapOf("f1" to Folder("f1", "Games", mutableListOf(Pin.App(app2)))),
            hidden = mutableSetOf(app2.key()),
            gates = mutableSetOf(app.key()),
            nextFolder = 2,
        )
        val back = LayoutData.parse(d.toJson())
        assertEquals(d.pins, back.pins)
        assertEquals(d.folders["f1"]?.name, back.folders["f1"]?.name)
        assertEquals(d.folders["f1"]?.items, back.folders["f1"]?.items)
        assertEquals(d.hidden, back.hidden)
        assertEquals(d.gates, back.gates)
        assertEquals(2, back.nextFolder)
    }

    @Test fun layoutRejectsBadShapes() {
        for (bad in listOf(
            "not json",
            """{"pins":[{"t":"folder","id":"nope"}]}""",
            """{"hidden":["no-slash"]}""",
            """{"folders":{"f1":{"name":"x","items":[{"t":"folder","id":"f1"}]}}}""",
            """{"pins":[{"t":"widget"}]}""",
        )) {
            try {
                LayoutData.parse(bad)
                fail("should reject: $bad")
            } catch (e: Exception) {
                // expected
            }
        }
    }

    @Test fun dropOnAnotherAppMakesAFolder() {
        val dir = createTempDir()
        try {
            val store = LayoutStore(dir, LauncherLog(dir))
            store.update { d ->
                d.pins.add(Pin.App(app))
                d.pins.add(Pin.App(app2))
            }
            pinDrop(store, 1, 0)
            val d = store.data.value
            assertEquals(1, d.pins.size)
            val ref = d.pins[0] as Pin.FolderRef
            assertEquals(2, d.folders[ref.folderId]?.items?.size)
            // And it is on disk.
            val onDisk = LayoutData.parse(File(dir, "layout.json").readText())
            assertEquals(d.pins, onDisk.pins)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun settingsValidation() {
        val ok = SettingDefs.validated(JSONObject("""{"words.count": 6, "home.clock": false}"""))
        assertEquals(6, ok["words.count"])
        for (bad in listOf(
            """{"words.count": 99}""",
            """{"home.clock": "yes"}""",
            """{"no.such": true}""",
        )) {
            try {
                SettingDefs.validated(JSONObject(bad))
                fail("should reject: $bad")
            } catch (e: Exception) {
                // expected
            }
        }
        assertTrue(SettingDefs.schema().contains("words.count"))
    }

    private fun createTempDir(): File {
        val f = File.createTempFile("layout-test", "")
        f.delete()
        f.mkdirs()
        return f
    }
}
