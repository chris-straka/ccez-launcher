package uk.ccez.launcher

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockWordsTest {
    @Test fun french() {
        assertEquals("Il est quinze heures douze", ClockWords.time("fr", LocalDateTime.of(2026, 11, 11, 15, 12)))
        assertEquals("mercredi onze novembre", ClockWords.date("fr", LocalDate.of(2026, 11, 11)))
        assertEquals("Il est une heure", ClockWords.time("fr", LocalDateTime.of(2026, 1, 1, 1, 0)))
        assertEquals("vingt et un", ClockWords.spell("fr", 21))
    }

    @Test fun others() {
        assertEquals("Es ist neun Uhr", ClockWords.time("de", LocalDateTime.of(2026, 1, 1, 9, 0)))
        assertEquals("einundzwanzig", ClockWords.spell("de", 21))
        assertEquals("十五時五分", ClockWords.time("ja", LocalDateTime.of(2026, 1, 1, 15, 5)))
        assertEquals("Son las tres y cinco", ClockWords.time("es", LocalDateTime.of(2026, 1, 1, 3, 5)))
        assertEquals("ventotto", ClockWords.spell("it", 28))
        assertEquals("42", ClockWords.spell("hy", 42))
        assertTrue(ClockWords.date("de", LocalDate.of(2026, 11, 11)).contains("November"))
    }

    @Test fun langOfDayRotates() {
        val langs = listOf("fr", "de", "es")
        assertTrue(LangOfDay.today(langs) in langs)
        assertEquals(null, LangOfDay.today(emptyList()))
    }
}
