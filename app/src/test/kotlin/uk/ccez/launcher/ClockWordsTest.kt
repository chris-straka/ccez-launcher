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
        // A new day, the next language (the clock picks it again at midnight).
        val day = LocalDate.of(2026, 11, 16)
        val next = langs[(langs.indexOf(LangOfDay.today(langs, day)) + 1) % langs.size]
        assertEquals(next, LangOfDay.today(langs, day.plusDays(1)))
    }

    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 11, 16, h, m)

    @Test fun frenchMidnightAndTheFirst() {
        assertEquals("Il est minuit", ClockWords.time("fr", at(0, 0)))
        assertEquals("Il est minuit douze", ClockWords.time("fr", at(0, 12)))
        assertEquals("dimanche premier novembre", ClockWords.date("fr", LocalDate.of(2026, 11, 1)))
    }

    @Test fun frenchHoursAndMinutesAreFeminine() {
        assertEquals("Il est vingt et une heures", ClockWords.time("fr", at(21, 0)))
        assertEquals("Il est vingt et une heures vingt et une", ClockWords.time("fr", at(21, 21)))
        assertEquals("Il est une heure une", ClockWords.time("fr", at(1, 1)))
        assertEquals("Il est minuit une", ClockWords.time("fr", at(0, 1)))
        assertEquals("Il est dix heures cinquante et une", ClockWords.time("fr", at(10, 51)))
        assertEquals("Il est onze heures", ClockWords.time("fr", at(11, 0)))
        assertEquals("Il est vingt-deux heures trente", ClockWords.time("fr", at(22, 30)))
    }

    @Test fun germanOneOclockAndOrdinalDates() {
        assertEquals("Es ist ein Uhr", ClockWords.time("de", at(1, 0)))
        assertEquals("Es ist ein Uhr fünf", ClockWords.time("de", at(1, 5)))
        assertEquals("Montag, der sechzehnte November", ClockWords.date("de", LocalDate.of(2026, 11, 16)))
        assertEquals("Sonntag, der erste November", ClockWords.date("de", LocalDate.of(2026, 11, 1)))
        assertEquals("Dienstag, der dritte November", ClockWords.date("de", LocalDate.of(2026, 11, 3)))
        assertEquals("Samstag, der einundzwanzigste November", ClockWords.date("de", LocalDate.of(2026, 11, 21)))
    }

    @Test fun chineseTwoOclockAndZeroMinutes() {
        assertEquals("两点", ClockWords.time("zh", at(2, 0)))
        assertEquals("两点零五分", ClockWords.time("zh", at(2, 5)))
        assertEquals("十四点二十分", ClockWords.time("zh", at(14, 20)))
        assertEquals("零点", ClockWords.time("zh", at(0, 0)))
        assertTrue(ClockWords.date("zh", LocalDate.of(2026, 2, 2)).startsWith("二月二日"))
    }

    @Test fun koreanNativeHoursAndMonths() {
        assertEquals("오후 세 시", ClockWords.time("ko", at(15, 0)))
        assertEquals("오전 한 시 오 분", ClockWords.time("ko", at(1, 5)))
        assertEquals("오전 열두 시", ClockWords.time("ko", at(0, 0)))
        assertEquals("오후 열두 시 삼십 분", ClockWords.time("ko", at(12, 30)))
        assertTrue(ClockWords.date("ko", LocalDate.of(2026, 6, 6)).startsWith("유월 육일"))
        assertTrue(ClockWords.date("ko", LocalDate.of(2026, 10, 10)).startsWith("시월 십일"))
    }

    @Test fun japaneseStays() {
        assertEquals("二時", ClockWords.time("ja", at(2, 0)))
        assertEquals("十一月十六日、月曜日", ClockWords.date("ja", LocalDate.of(2026, 11, 16)))
    }

    @Test fun spanishItalianPortugueseForms() {
        assertEquals("dieciséis", ClockWords.spell("es", 16))
        assertEquals("veintidós", ClockWords.spell("es", 22))
        assertEquals("veintitrés", ClockWords.spell("es", 23))
        assertEquals("veintiséis", ClockWords.spell("es", 26))
        assertEquals("Es medianoche", ClockWords.time("es", at(0, 0)))
        assertEquals("Son las veintiuna y diez", ClockWords.time("es", at(21, 10)))
        assertEquals("lunes dieciséis de noviembre", ClockWords.date("es", LocalDate.of(2026, 11, 16)))
        assertEquals("ventitré", ClockWords.spell("it", 23))
        assertEquals("trentatré", ClockWords.spell("it", 33))
        assertEquals("È mezzanotte", ClockWords.time("it", at(0, 0)))
        assertEquals("São duas e cinco", ClockWords.time("pt", at(2, 5)))
        assertTrue(ClockWords.date("pt", LocalDate.of(2026, 11, 1)).endsWith("primeiro de novembro"))
    }
}
