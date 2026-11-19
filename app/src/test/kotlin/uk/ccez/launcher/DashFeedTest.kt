package uk.ccez.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashFeedTest {
    private val sample = """
        {"v":1,"at":1795050000000,
         "machines":[{"name":"z","state":"idle","jobs":0,"note":"seat"},{"name":"f","state":"busy","jobs":5,"note":""},
                     {"name":"basement","state":"off","jobs":0,"note":"off line 3 h"}],
         "running":{"count":7,"groups":[{"name":"HLH","jobs":3,"who":"claude","line":"gk check on f · 12 min"}]},
         "queue":{"count":18,"oldest_min":42},
         "attention":[{"level":"red","text":"Sync repos failed on y8"}],
         "carbon":[{"level":"amber","text":"retopo: no output for 12 min"}],
         "games":[{"id":"hlh","name":"HLH","fresh":false,"line":"3 commits behind, built 16:51","building":"","failed":"",
                   "notes":2,"can_build":true,"why_not":""}],
         "check":{"ok":false,"text":"Night check at 03:12: 2 red, 4 green\nhlh: test\nundertow: android"},
         "claude":{"level":"amber","text":"Claude: 84% of the week used"},
         "ai_watch":{"date":"2026-11-18","text":"Nothing notable."},
         "quiet":{"on":false,"can":true}}
    """.trimIndent()

    @Test fun readsEveryPart() {
        val f = DashFeed.parse(sample)
        assertEquals(1795050000000L, f.at)
        assertEquals(listOf("z", "f", "basement"), f.machines.map { it.name })
        assertEquals("off", f.machines[2].state)
        assertEquals(5, f.machines[1].jobs)
        assertEquals(7, f.running)
        assertEquals("HLH", f.groups.single().name)
        assertEquals(18, f.queued)
        assertEquals(42, f.oldestQueuedMin)
        assertEquals("red", f.attention.single().level)
        assertEquals("amber", f.carbon.single().level)
        val g = f.games.single()
        assertEquals("hlh", g.id)
        assertFalse(g.fresh)
        assertTrue(g.canBuild)
        assertEquals(2, g.notes)
        assertEquals("red", f.check?.level)
        assertEquals("amber", f.claude?.level)
        assertEquals("2026-11-18", f.aiWatchDate)
        assertFalse(f.quiet)
        assertTrue(f.canQuiet)
    }

    @Test fun missingPartsReadAsEmpty() {
        val f = DashFeed.parse("""{"at":5}""")
        assertTrue(f.machines.isEmpty() && f.games.isEmpty() && f.attention.isEmpty())
        assertEquals(0, f.running)
        assertNull(f.check)
        assertEquals("", f.aiWatch)
        assertFalse(f.canQuiet)
    }

    @Test fun stripNamesTheWorstFirstInOneLineEach() {
        val f = DashFeed.parse(sample)
        val texts = stripFlags(f).map { it.text }
        assertEquals(
            listOf("Sync repos failed on y8", "Night check at 03:12: 2 red, 4 green", "Claude: 84% of the week used"),
            texts,
        )
        val failedRuns = f.copy(carbon = listOf(DashFlag("red", "a failed"), DashFlag("red", "b failed"), DashFlag("amber", "c quiet")))
        assertTrue("2 Carbon runs failed" in stripFlags(failedRuns).map { it.text })
    }

    @Test fun machinesShortAndRunningInOneLine() {
        assertEquals("studio", shortName("studio-macbook-pro-4"))
        assertEquals("y8", shortName("y8"))
        val f = DashFeed.parse(sample)
        assertEquals("HLH 3 · 18 queued", runningLine(f))
        assertEquals("Nothing running", runningLine(f.copy(groups = emptyList(), queued = 0)))
    }

    @Test fun answersAreOkAndWords() {
        assertEquals(false to "HLH is running: close it first", DashFeed.answer("""{"ok":false,"text":"HLH is running: close it first"}"""))
        assertEquals(true to "Rebuilding HLH", DashFeed.answer("""{"ok":true,"text":"Rebuilding HLH"}"""))
    }
}
