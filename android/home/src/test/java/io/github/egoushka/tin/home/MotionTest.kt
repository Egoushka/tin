package io.github.egoushka.tin.home

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import io.github.egoushka.tin.home.theme.LightKind
import io.github.egoushka.tin.home.theme.LightMotion
import io.github.egoushka.tin.home.theme.Motion
import io.github.egoushka.tin.home.theme.MotionSpec
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** M1-M5: every transition of cards/Light.md maps to its Material 3 duration and curve, and none moves with motion off. */
class MotionTest {
    private val design = File(System.getProperty("tin.design") ?: "../../docs/design/night-glass")

    private val tokens = mapOf("short2" to Motion.SHORT2, "short4" to Motion.SHORT4, "medium2" to Motion.MEDIUM2,
        "long2" to Motion.LONG2, "extraLong4" to Motion.EXTRA_LONG4)
    private val curves = mapOf("standard" to Motion.Standard, "emphasized decelerate" to Motion.EmphasizedDecelerate,
        "emphasized accelerate" to Motion.EmphasizedAccelerate)

    /** The table of cards/Light.md: transition name to its duration token and curve name. */
    private val table: Map<String, Pair<String, String>> = File(design, "cards/Light.md").readLines()
        .mapNotNull { Regex("""^\| ([^|]+?) \| [^|]+ \| \d+ ms \(`(\w+)`\) \| ([^|]+?) \|\s*$""").matchEntire(it)?.destructured }
        .associate { (name, token, curve) -> name to (token to curve) }

    private fun row(name: String): Pair<Int, Easing> {
        val (token, curve) = table[name] ?: error("Light.md has no row \"$name\"")
        return (tokens[token] ?: error("no token $token")) to (curves[curve] ?: error("no curve $curve"))
    }

    private fun assertRow(name: String, spec: MotionSpec) {
        val (ms, easing) = row(name)
        assertEquals("$name duration", ms, spec.ms)
        assertSame("$name curve", easing, spec.easing)
    }

    @Test
    fun tableHasTheSixTransitions() {
        assertEquals(
            setOf("Calm → needs you", "Needs you → calm", "Calm → now", "Any → broken", "Any → stale", "Stale → fresh"),
            table.keys,
        )
    }

    @Test
    fun everyTransitionMapsToItsRow() {
        assertRow("Calm → needs you", LightMotion.transition(LightKind.Calm, LightKind.NeedsYou))
        assertRow("Needs you → calm", LightMotion.transition(LightKind.NeedsYou, LightKind.Calm))
        assertRow("Calm → now", LightMotion.transition(LightKind.Calm, LightKind.Now))
        for (from in listOf(LightKind.Calm, LightKind.Now, LightKind.NeedsYou)) {
            assertRow("Any → broken", LightMotion.transition(from, LightKind.Broken))
        }
        assertRow("Any → stale", LightMotion.staleFade(true))
        assertRow("Stale → fresh", LightMotion.staleFade(false))
    }

    @Test
    fun curveTokensAreTheMaterialControlPoints() {
        val reference = mapOf(
            Motion.Standard to CubicBezierEasing(0.2f, 0f, 0f, 1f),
            Motion.EmphasizedDecelerate to CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f),
            Motion.EmphasizedAccelerate to CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f),
        )
        for ((token, want) in reference) {
            for (x in listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f)) assertEquals(want.transform(x), token.transform(x), 1e-4f)
        }
        // the three are told apart: a token that silently became another one would pass the row checks above
        assertTrue(Motion.EmphasizedDecelerate.transform(0.25f) > Motion.Standard.transform(0.25f))
        assertTrue(Motion.EmphasizedAccelerate.transform(0.25f) < Motion.Standard.transform(0.25f))
    }

    @Test
    fun theLightThatArrivesIsTheLightThatAnimates() {
        assertRow("Calm → needs you", LightMotion.alpha(LightKind.NeedsYou, active = true))
        assertRow("Needs you → calm", LightMotion.alpha(LightKind.NeedsYou, active = false))
        assertRow("Calm → now", LightMotion.alpha(LightKind.Now, active = true))
        assertRow("Any → broken", LightMotion.alpha(LightKind.Broken, active = true))
    }

    /** The assumption in the spec: a light that is not in the table leaves at the needs-you → calm row. */
    @Test
    fun unlistedExitsLeaveAtTheNeedsYouRow() {
        for (from in listOf(LightKind.Now, LightKind.Broken, LightKind.NeedsYou)) {
            assertRow("Needs you → calm", LightMotion.transition(from, LightKind.Calm))
        }
        assertRow("Needs you → calm", LightMotion.alpha(LightKind.Broken, active = false))
        assertRow("Needs you → calm", LightMotion.alpha(LightKind.Now, active = false))
    }

    @Test
    fun aSwapBetweenLitKindsArrivesAsTheNewKind() {
        assertRow("Calm → needs you", LightMotion.transition(LightKind.Broken, LightKind.NeedsYou))
        assertRow("Any → broken", LightMotion.transition(LightKind.NeedsYou, LightKind.Broken))
        assertRow("Calm → now", LightMotion.transition(LightKind.NeedsYou, LightKind.Now))
    }

    private val everySpec: List<MotionSpec> = buildList {
        for (from in LightKind.entries) for (to in LightKind.entries) add(LightMotion.transition(from, to))
        add(LightMotion.staleFade(true))
        add(LightMotion.staleFade(false))
        add(LightMotion.focus)
        add(LightMotion.press)
    }

    @Test
    fun withMotionOffEveryTransitionIsInstant() {
        for (spec in everySpec) {
            assertEquals(0, Motion.duration(spec.ms, 0f))
            assertEquals(0, Motion.tween(spec, 0f).durationMillis)
            assertEquals(spec.ms, Motion.tween(spec, 1f).durationMillis)
        }
    }

    @Test
    fun focusDimLastsTheCssTransition() {
        assertEquals(220, LightMotion.focus.ms)
        assertSame(Motion.Standard, LightMotion.focus.easing)
    }

    @Test
    fun focusDimsCalmCardsOnly() {
        assertEquals(0.4f, LightMotion.focusDim(true, LightKind.Calm, keepBright = false), 0f)
        assertEquals(1f, LightMotion.focusDim(true, LightKind.Calm, keepBright = true), 0f)
        assertEquals(1f, LightMotion.focusDim(true, LightKind.NeedsYou, keepBright = false), 0f)
        assertEquals(1f, LightMotion.focusDim(true, LightKind.Broken, keepBright = false), 0f)
        assertEquals(1f, LightMotion.focusDim(false, LightKind.Calm, keepBright = false), 0f)
    }

    /** "Never vibrate": light is the only signal, so the layers that draw it carry no haptics at all. */
    @Test
    fun noHapticsInTheLayersThatDrawLight() {
        val haptics = Regex("""Vibrator|VibrationEffect|HapticFeedback|performHapticFeedback""")
        val root = File("src/main/java/io/github/egoushka/tin/home")
        val files = listOf("layer", "theme").flatMap { File(root, it).walkTopDown().filter { f -> f.extension == "kt" }.toList() }
        assertTrue("found no sources under $root", files.isNotEmpty())
        for (f in files) assertFalse("${f.name} uses haptics", haptics.containsMatchIn(f.readText()))
    }
}
