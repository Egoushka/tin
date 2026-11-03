package io.github.egoushka.tin.home.gestures

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** G3, G4: what two fingers did, from where they started and where they were last seen. */
class MultiTouchTest {
    private val travel = 48f

    private fun move(
        a0: Offset, b0: Offset, a1: Offset, b1: Offset,
    ): TwoFinger? = TwoFinger.of(a0, b0, a1, b1, travel)

    private val a = Offset(100f, 400f)
    private val b = Offset(200f, 400f)

    @Test fun `both fingers up by a target's travel or more is a swipe up`() {
        assertEquals(TwoFinger.SwipeUp, move(a, b, a + Offset(0f, -60f), b + Offset(0f, -60f)))
    }

    @Test fun `both fingers down is a swipe down`() {
        assertEquals(TwoFinger.SwipeDown, move(a, b, a + Offset(0f, 150f), b + Offset(5f, 140f)))
    }

    @Test fun `a short drag is nothing`() {
        assertNull(move(a, b, a + Offset(0f, -20f), b + Offset(0f, -20f)))
    }

    @Test fun `a sideways or diagonal drift is nothing`() {
        assertNull(move(a, b, a + Offset(150f, 0f), b + Offset(150f, 0f)))
        assertNull(move(a, b, a + Offset(100f, 100f), b + Offset(100f, 100f)))
    }

    @Test fun `fingers moving opposite ways are not a swipe`() {
        assertNull(move(a, b, a + Offset(0f, -100f), b + Offset(0f, 100f)))
    }

    @Test fun `fingers closing to 70 percent of their span are a pinch in`() {
        assertEquals(TwoFinger.PinchIn, move(a, b, a + Offset(30f, 0f), b + Offset(-30f, 0f)))
        assertEquals(TwoFinger.PinchIn, move(a, b, Offset(150f, 400f), Offset(150f, 400f)))
    }

    @Test fun `closing a little or spreading is not a pinch in`() {
        assertNull(move(a, b, a + Offset(10f, 0f), b + Offset(-10f, 0f)))
        assertNull(move(a, b, a + Offset(-60f, 0f), b + Offset(60f, 0f)))
    }

    @Test fun `closing while the middle travels a target is a swipe, not a pinch`() {
        assertEquals(TwoFinger.SwipeDown, move(a, b, a + Offset(30f, 90f), b + Offset(-30f, 90f)))
    }
}
