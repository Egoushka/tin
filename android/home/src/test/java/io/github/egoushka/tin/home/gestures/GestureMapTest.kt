package io.github.egoushka.tin.home.gestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** G1, G2, G12: the card's defaults, the stored form, and what an action does. */
class GestureMapTest {
    private class Rec : GestureEffects {
        val calls = mutableListOf<String>()
        override fun search() { calls += "search" }
        override fun notifications() { calls += "notifications" }
        override fun quickSettings() { calls += "quickSettings" }
        override fun camera() { calls += "camera" }
        override fun torch() { calls += "torch" }
        override fun today() { calls += "today" }
        override fun editHome() { calls += "editHome" }
    }

    @Test fun `the defaults are the card's table`() {
        val m = GestureMap()
        assertEquals(GestureAction.AllApps, m.of(Gesture.SwipeUp))
        assertEquals(GestureAction.Notifications, m.of(Gesture.SwipeDown))
        assertEquals(GestureAction.QuickSettings, m.of(Gesture.TwoFingerDown))
        assertEquals(GestureAction.Camera, m.of(Gesture.TwoFingerUp))
        assertEquals(GestureAction.Torch, m.of(Gesture.DoubleTap))
        assertEquals(GestureAction.Today, m.of(Gesture.PinchIn))
        assertEquals(GestureAction.EditHome, m.of(Gesture.LongPress))
        assertEquals(7, Gesture.entries.size)
    }

    @Test fun `a map survives encode and decode`() {
        val m = GestureMap().with(Gesture.DoubleTap, GestureAction.Nothing).with(Gesture.PinchIn, GestureAction.Camera)
        assertEquals(m, GestureMap.decode(m.encode()))
        assertEquals(GestureMap(), GestureMap.decode(GestureMap().encode()))
    }

    @Test fun `unknown names and a missing value read as the defaults`() {
        assertEquals(GestureMap(), GestureMap.decode(null))
        assertEquals(GestureMap(), GestureMap.decode(""))
        assertEquals(GestureMap(), GestureMap.decode("Shake:Torch,DoubleTap:Teleport,garbage"))
        assertEquals(GestureAction.Nothing, GestureMap.decode("Shake:Torch,SwipeUp:Nothing").of(Gesture.SwipeUp))
    }

    @Test fun `reassigning one gesture changes no other`() {
        val m = GestureMap().with(Gesture.SwipeUp, GestureAction.Torch)
        for (g in Gesture.entries - Gesture.SwipeUp) assertEquals(g.default, m.of(g))
        assertEquals(GestureAction.Torch, m.of(Gesture.SwipeUp))
        assertEquals(GestureMap(), m.with(Gesture.SwipeUp, Gesture.SwipeUp.default))
    }

    @Test fun `next walks every action and comes back`() {
        var a = GestureAction.AllApps
        val seen = mutableSetOf<GestureAction>()
        repeat(GestureAction.entries.size) { seen += a; a = a.next() }
        assertEquals(GestureAction.AllApps, a)
        assertEquals(GestureAction.entries.toSet(), seen)
    }

    @Test fun `every action takes one step and Nothing takes none`() {
        val expect = mapOf(
            GestureAction.AllApps to "search", GestureAction.Notifications to "notifications",
            GestureAction.QuickSettings to "quickSettings", GestureAction.Camera to "camera",
            GestureAction.Torch to "torch", GestureAction.Today to "today", GestureAction.EditHome to "editHome",
        )
        for (a in GestureAction.entries - GestureAction.Nothing) {
            val r = Rec()
            a.run(r)
            assertEquals(listOf(expect.getValue(a)), r.calls)
        }
        val none = Rec()
        GestureAction.Nothing.run(none)
        assertTrue(none.calls.isEmpty())
    }
}
