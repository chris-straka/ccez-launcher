package uk.ccez.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchLogicTest {
    @Test fun sums() {
        assertEquals("14", Calc.eval("2+3*4"))
        assertEquals("20", Calc.eval("(2+3)*4"))
        assertEquals("1024", Calc.eval("2^10"))
        assertEquals("-3", Calc.eval("2-5"))
        assertEquals("1.5", Calc.eval("3/2"))
        assertEquals("1", Calc.eval("10%3"))
        assertNull(Calc.eval("hello"))
        assertNull(Calc.eval("2+"))
        assertNull(Calc.eval("4"))
        assertNull(Calc.eval("1/0"))
        assertNull(Calc.eval("2++"))
    }

    @Test fun conversions() {
        assertEquals("3.10686", Units.convert("5 km to mi")?.value)
        assertEquals("1000", Units.convert("1 kg to g")?.value)
        assertEquals("22.2222", Units.convert("72 f to c")?.value)
        assertEquals("-40", Units.convert("-40 c to f")?.value)
        assertEquals("60", Units.convert("1 h to min")?.value)
        assertEquals("2.20462", Units.convert("1 kg to lb")?.value)
        assertNull(Units.convert("5 km to kg"))
        assertNull(Units.convert("five km to mi"))
        assertNull(Units.convert("5 km"))
    }

    @Test fun matchingIgnoresCaseAndAccents() {
        assertEquals(0, matchRank("Café", normalized("caf")))
        assertEquals(0, matchRank("Google Maps", normalized("map")))
        assertEquals(1, matchRank("Chrome", normalized("rom")))
        assertEquals(-1, matchRank("Chrome", normalized("xyz")))
    }

    @Test fun columnsGrowWithWidth() {
        assertEquals(4, columnsFor(320))
        assertEquals(5, columnsFor(411))
        assertEquals(8, columnsFor(1200))
    }
}
