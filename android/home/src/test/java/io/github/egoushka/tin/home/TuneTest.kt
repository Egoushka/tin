package io.github.egoushka.tin.home

import io.github.egoushka.tin.home.theme.Motion
import io.github.egoushka.tin.home.tune.Preset
import io.github.egoushka.tin.home.tune.Tune
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** H7 and H8: Tune knobs, presets and the frost-0 fallback. */
class TuneTest {
    @Test
    fun presetsMatchTheTuneCard() {
        assertEquals(Tune(), Tune.preset(Preset.Default))
        Tune.preset(Preset.Bedside).let { assertEquals(0.4f, it.roomLight); assertEquals(0.7f, it.glassTint) }
        Tune.preset(Preset.Battery).let { assertEquals(0f, it.frostDp); assertEquals(0.6f, it.roomLight) }
        Tune.preset(Preset.DeepWork).let { assertTrue(it.focus); assertEquals(8f, it.gapDp) }
    }

    @Test
    fun aChangeByHandClearsThePreset() {
        val t = Tune.preset(Preset.Bedside).edit { copy(roomLight = 0.5f) }
        assertEquals(Preset.Custom, t.preset)
        assertEquals(0.5f, t.roomLight)
    }

    @Test
    fun knobsStayInRange() {
        val t = Tune().edit { copy(roomLight = 9f, frostDp = -3f, glassTint = 0.1f, gapDp = 11f, strokeDp = 1.9f) }
        assertEquals(1.4f, t.roomLight)
        assertEquals(0f, t.frostDp)
        assertEquals(0.35f, t.glassTint) // contrast floor
        assertEquals(10f, t.gapDp)
        assertEquals(2f, t.strokeDp)
        assertEquals(12f, Tune(gapDp = 10f).padDp)
    }

    @Test
    fun frostZeroUsesNoRenderEffect() {
        assertFalse(Tune.preset(Preset.Battery).usesFrost)
        assertTrue(Tune().usesFrost)
    }

    @Test
    fun countsChangesFromDefault() {
        assertEquals(0, Tune().changedFromDefault())
        assertEquals(2, Tune.preset(Preset.Battery).changedFromDefault())
    }

    @Test
    fun reducedMotionIsInstant() {
        assertEquals(0, Motion.duration(Motion.NEEDS_YOU_IN, 0f))
        assertEquals(300, Motion.duration(Motion.NEEDS_YOU_IN, 1f))
    }
}
