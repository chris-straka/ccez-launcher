package uk.ccez.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SizesTest {
    @Test fun widgetMinimumsArePixelsNotDp() {
        // 330 px at 3x density is 110 dp, not 220 (the clamp) as before.
        assertEquals(listOf(110f), Sizes.widgetHeights(listOf(330), 3f, 500f))
        // Tiny ones still get 72 dp, huge ones 220.
        assertEquals(listOf(72f, 220f), Sizes.widgetHeights(listOf(30, 3000), 3f, 500f))
    }

    @Test fun manyWidgetsShrinkTogetherUnderTheCap() {
        val h = Sizes.widgetHeights(listOf(660, 660, 660), 3f, 180f)
        assertEquals(3, h.size)
        assertEquals(180f, h.sum(), 0.01f)
        assertTrue(h.all { it == h[0] })
    }

    @Test fun continueSkipsWhatIsntInTheDrawer() {
        val drawer = setOf("fr.example.journal", "de.example.karte")
        // Karte, then the Recents screen and a permission dialog came to the front.
        val resumed = listOf("fr.example.journal", "de.example.karte", "com.sec.android.app.launcher", "com.google.android.permissioncontroller")
        assertEquals("de.example.karte", LastApp.lastLaunchable(resumed, drawer))
        assertEquals(null, LastApp.lastLaunchable(listOf("com.android.systemui"), drawer))
        // The launcher itself never counts, even if listed.
        assertEquals("fr.example.journal", LastApp.lastLaunchable(listOf("fr.example.journal", "uk.ccez.launcher"), drawer + "uk.ccez.launcher"))
    }
}
