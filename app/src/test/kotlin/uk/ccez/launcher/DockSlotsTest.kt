package uk.ccez.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The dock grid's cells under a finger, for a lifted pin (DockSlots). */
class DockSlotsTest {
    // Four columns of 100 px, rows of 200 px, seven pins: 4 + 3.
    private fun at(x: Float, y: Float) = DockSlots.at(x, y, 4, 100f, 200f, 7)
    private fun exact(x: Float, y: Float) = DockSlots.exact(x, y, 4, 100f, 200f, 7)

    @Test fun aFingerOverACellIsThatCell() {
        assertEquals(0, at(50f, 100f))
        assertEquals(3, at(399f, 10f))
        assertEquals(5, at(150f, 250f))
    }

    @Test fun outsideTheGridItLandsOnTheNearestCell() {
        assertEquals(0, at(-80f, -300f))
        assertEquals(3, at(900f, 50f))
        // Below the last row, and past its last pin: the last pin's cell.
        assertEquals(6, at(390f, 900f))
        assertEquals(4, at(10f, 900f))
    }

    @Test fun aTapCountsOnlyOnACell() {
        assertEquals(2, exact(250f, 100f))
        assertEquals(6, exact(250f, 300f))
        assertNull("the empty eighth cell", exact(350f, 300f))
        assertNull(exact(-1f, 10f))
        assertNull(exact(410f, 10f))
        assertNull(exact(10f, 410f))
        assertNull("not measured yet", DockSlots.exact(10f, 10f, 4, 100f, 0f, 7))
    }

    @Test fun theOthersMakeRoomForTheLiftedPin() {
        val row = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("b", "c", "a", "d", "e"), DockSlots.preview(row, 0, 2))
        assertEquals(listOf("d", "a", "b", "c", "e"), DockSlots.preview(row, 3, 0))
        assertEquals(row, DockSlots.preview(row, 1, 1))
        assertEquals(listOf("a", "c", "d", "e", "b"), DockSlots.preview(row, 1, 9))
        assertEquals(row, DockSlots.preview(row, 7, 0))
    }

    @Test fun droppingWhereThePreviewShowedItIsTheMove() {
        // The preview and LayoutOps.move agree: the pin ends where it was shown.
        val d = LayoutData()
        listOf("a", "b", "c", "d").forEach { d.pins.add(Pin.App(AppId(it, ".Main", 0))) }
        val shown = DockSlots.preview(d.pins.toList(), 0, 2)
        LayoutOps.move(d, 0, 2)
        assertEquals(shown, d.pins)
    }
}
