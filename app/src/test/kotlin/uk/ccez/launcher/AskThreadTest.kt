package uk.ccez.launcher

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskThreadTest {
    private val q1 = AskTurn(user = true, text = "Que veut dire « déchu » ?")
    private val a1 = AskTurn(user = false, text = "Fallen.")
    private val q2 = AskTurn(user = true, text = "Et « allerdings » ?")

    @Test fun theThreadGoesWithoutFailedOrUnfinishedAnswers() {
        val failed = AskTurn(user = false, text = "", failed = "No connection to DeepSeek.")
        val streaming = AskTurn(user = false, text = "Howev", done = false)
        val a = JSONArray(AskThread.messages(listOf(q1, a1, q2, failed, streaming)))
        assertEquals(3, a.length())
        assertEquals("user", a.getJSONObject(0).getString("role"))
        assertEquals("assistant", a.getJSONObject(1).getString("role"))
        assertEquals("Et « allerdings » ?", a.getJSONObject(2).getString("content"))
    }

    @Test fun itClearsAfterTheIdleTimeButNeverMidAnswer() {
        val five = 5 * 60_000L
        assertFalse(AskThread.stale(emptyList(), five * 2, 5))
        assertFalse(AskThread.stale(listOf(q1, a1), five - 1, 5))
        assertTrue(AskThread.stale(listOf(q1, a1), five, 5))
        assertFalse(AskThread.stale(listOf(q1, AskTurn(user = false, text = "…", done = false)), five * 3, 5), "an answer still coming")
        assertTrue(AskThread.stale(listOf(q1, a1), 60_000L, 1))
    }

    @Test fun threadIdsAreWhatCcezLlmAccepts() {
        repeat(20) { assertTrue(Regex("^[A-Za-z0-9-]{1,64}$").matches(AskThread.newId())) }
    }

    @Test fun markdownShowsAsStyleNotAsStars() {
        val s = AskThread.spans("« Déchu » means **fallen, deposed** — *e.g.* a `roi déchu`.")
        assertEquals("« Déchu » means ", s[0].text)
        assertEquals(AskThread.Span("fallen, deposed", bold = true), s[1])
        assertEquals(AskThread.Span("e.g.", italic = true), s[3])
        assertEquals(AskThread.Span("roi déchu", code = true), s[5])
        assertEquals("« Déchu » means fallen, deposed — e.g. a roi déchu.", s.joinToString("") { it.text })
        assertEquals("• un\n• deux", AskThread.spans("- un\n* deux").joinToString("") { it.text })
        assertEquals("2 * 3 = 6", AskThread.spans("2 * 3 = 6").joinToString("") { it.text })
    }

    private fun assertFalse(cond: Boolean, msg: String) = org.junit.Assert.assertFalse(msg, cond)
}
