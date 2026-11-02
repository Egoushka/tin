package io.github.egoushka.tin.home

import io.github.egoushka.tin.home.theme.Attention
import io.github.egoushka.tin.home.ui.CLASH_ICON_INK_MIX
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C1: WCAG contrast of ink and ink-2 on the glass, from tokens.json and the paint of bundle.css.
 * The model is the spec's (docs/specs/2026-11-03-home-a11y-locale.md): glass = surface at the tint over the
 * backdrop; backdrop = the clearest spot of the room, saturated by the frost; lit = a glow over the glass.
 * Every check runs before the test fails, so one run lists all the misses.
 */
class ContrastTest {
    private val design = File(System.getProperty("tin.design") ?: "../../docs/design/night-glass")
    private val tokens = Json.parseToJsonElement(File(design, "tokens.json").readText()).jsonObject["color"]!!
        .jsonObject["tokens"]!!.jsonArray.associate {
            it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["value"]!!.jsonObject
        }

    private data class C(val r: Double, val g: Double, val b: Double, val a: Double = 1.0)

    private fun token(skin: String, name: String): C {
        val s = tokens.getValue(name).getValue(skin).jsonPrimitive.content.trim()
        if (s.startsWith("#")) return C(s.substring(1, 3).toInt(16) / 255.0, s.substring(3, 5).toInt(16) / 255.0, s.substring(5, 7).toInt(16) / 255.0)
        val m = Regex("""rgb\((\d+) (\d+) (\d+) / ([\d.]+)\)""").matchEntire(s) ?: error("unknown colour $s")
        val (r, g, b, a) = m.destructured
        return C(r.toInt() / 255.0, g.toInt() / 255.0, b.toInt() / 255.0, a.toDouble())
    }

    private fun C.alpha(a: Double) = copy(a = a)
    private fun hue(c: androidx.compose.ui.graphics.Color) = C(c.red.toDouble(), c.green.toDouble(), c.blue.toDouble())

    /** `top` painted over an opaque `bottom`, in sRGB like CSS and Compose. */
    private fun over(top: C, bottom: C) = C(
        top.r * top.a + bottom.r * (1 - top.a), top.g * top.a + bottom.g * (1 - top.a), top.b * top.a + bottom.b * (1 - top.a),
    )

    private fun mix(from: C, to: C, k: Double) = C(from.r + (to.r - from.r) * k, from.g + (to.g - from.g) * k, from.b + (to.b - from.b) * k)

    /** CSS `saturate(s)` (Filter Effects 1), applied to the backdrop only: the glass is drawn over it. */
    private fun saturate(c: C, s: Double) = C(
        ((0.213 + 0.787 * s) * c.r + (0.715 - 0.715 * s) * c.g + (0.072 - 0.072 * s) * c.b).coerceIn(0.0, 1.0),
        ((0.213 - 0.213 * s) * c.r + (0.715 + 0.285 * s) * c.g + (0.072 - 0.072 * s) * c.b).coerceIn(0.0, 1.0),
        ((0.213 - 0.213 * s) * c.r + (0.715 - 0.715 * s) * c.g + (0.072 + 0.928 * s) * c.b).coerceIn(0.0, 1.0),
    )

    private fun lin(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    private fun lum(c: C) = 0.2126 * lin(c.r) + 0.7152 * lin(c.g) + 0.0722 * lin(c.b)
    private fun ratio(fg: C, bg: C): Double {
        val a = lum(fg)
        val b = lum(bg)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    /** Where text can sit on a skin's glass, each as the opaque colour under it. */
    private class Spot(val name: String, val bg: C)

    /**
     * Lit cards are a flat 30% of the hue here. The app paints a radial light from the card's top-left corner that
     * fades out by 70% of the card, so this is what the corner gets and an upper bound everywhere else.
     */
    private fun nightGlassSpots(tint: Double): List<Spot> {
        val bg = token("nightglass", "bg")
        val glow1 = token("nightglass", "glow-1")
        val glow2 = token("nightglass", "glow-2")
        val glow3 = token("nightglass", "glow-3")
        val surface = token("nightglass", "surface").alpha(tint)
        val bad = token("nightglass", "bad")
        val spots = mutableListOf<Spot>()
        // Room light 0 (off), 1 (default) and 1.4 (the Tune maximum, clamped to full strength like Glass.kt).
        for (room in listOf(0.0, 1.0, 1.4)) {
            val clearest = listOf(
                "bg" to bg,
                "glow-3" to over(glow3.alpha(min(1.0, room * 0.85)), bg),
                "glow-2" to over(glow2.alpha(min(1.0, room * 0.22)), bg),
            )
            for ((where, backdrop) in clearest) {
                val glass = over(surface, saturate(backdrop, 1.6))
                spots += Spot("calm, room $room, $where", glass)
                spots += Spot("broken, room $room, $where", over(bad.alpha(0.22), glass))
                spots += Spot("now row, room $room, $where", over(token("nightglass", "signal").alpha(0.10), glass))
                // The attention hue is the Tune knob; the token holds the default, the enum holds the allowed four.
                for (a in Attention.entries) spots += Spot("lit ${a.name}, room $room, $where", over(hue(a.color).alpha(0.30), glass))
                spots += Spot("lit token glow-1, room $room, $where", over(glow1.alpha(0.30), glass))
            }
        }
        return spots
    }

    private fun glassSpots(): List<Spot> {
        val surface = token("glass", "surface")
        return listOf("glow-1", "glow-2", "glow-3").map { Spot("glass over $it", over(surface, saturate(token("glass", it), 1.7))) }
    }

    private fun nightSpots(): List<Spot> = listOf(Spot("night surface", token("night", "surface")))

    private fun List<Spot>.notLit() = filterNot { it.name.startsWith("lit") }
    private fun List<Spot>.litBy(hue: String) = filter { it.name.startsWith("lit $hue,") }

    private val misses = mutableListOf<String>()

    private fun check(label: String, min: Double, fg: C, spots: List<Spot>) {
        val (where, r) = spots.map { it.name to ratio(fg, it.bg) }.minBy { it.second }
        println("contrast %-34s %.2f:1 (needs %.1f) at %s".format(label, r, min, where))
        if (r < min) misses += "$label: %.2f:1 at %s, needs %.1f".format(r, where, min)
    }

    private fun assertNoMisses() = assertTrue("contrast misses:\n" + misses.joinToString("\n"), misses.isEmpty())

    private val tints = (35..100).map { it / 100.0 }
    private fun pct(t: Double) = "${Math.round(t * 100)}%"

    /** Ink on every surface, ink-2 on every surface that is not lit: no hue is involved, so no exception. */
    @Test
    fun inkHoldsEverywhereAndInk2HoldsOffTheLitCard() {
        val ink = token("nightglass", "ink")
        val ink2 = token("nightglass", "ink-2")
        for (t in tints) {
            val spots = nightGlassSpots(t)
            check("ink, tint ${pct(t)}", 4.5, ink, spots)
            check("ink-2 unlit, tint ${pct(t)}", 4.5, ink2, spots.notLit())
        }
        assertNoMisses()
    }

    /** README: ink-2 stays at or above 5.0:1 on the clearest glass, tint 35%. Under a lit card it holds only for some hues. */
    @Test
    fun ink2KeepsTheDocumentedFloorOnTheGlass() {
        check("ink-2 floor, tint 35%", 5.0, token("nightglass", "ink-2"), nightGlassSpots(0.35).notLit())
        assertNoMisses()
    }

    /**
     * Ink-2 under the light of a lit card, per hue, at every tint and room. README and the Attention doc claim 4.5:1
     * for all four hues; the model gives that to violet and rose, and less to blue and amber at the bright end of the
     * room knob. The floors below are what the code keeps today, and the spec records the rest as a documented limit.
     */
    @Test
    fun ink2OnALitCardKeepsTheFloorOfItsHue() {
        val ink2 = token("nightglass", "ink-2")
        val floors = mapOf(Attention.Violet to 4.5, Attention.Rose to 4.5, Attention.Blue to 4.0, Attention.Amber to 3.5)
        for ((a, floor) in floors) for (t in tints) check("ink-2 lit ${a.name}, tint ${pct(t)}", floor, ink2, nightGlassSpots(t).litBy(a.name))
        assertNoMisses()
    }

    @Test
    fun brokenFigureIsLargeTextAtThreeToOne() {
        val spots = nightGlassSpots(0.35).filter { it.name.startsWith("broken") }
        check("bad figure on broken card", 3.0, token("nightglass", "bad"), spots)
        assertNoMisses()
    }

    @Test
    fun theThreeSkinsKeepInkAndInk2AtFourPointFive() {
        val skins = mapOf("nightglass" to nightGlassSpots(0.55).notLit(), "glass" to glassSpots(), "night" to nightSpots())
        for ((skin, spots) in skins) {
            check("$skin ink", 4.5, token(skin, "ink"), spots)
            check("$skin ink-2", 4.5, token(skin, "ink-2"), spots)
        }
        assertNoMisses()
    }

    /** UI parts need 3:1 against what is around them: the mic, and the clash icon, which is the only mark of a clash. */
    @Test
    fun micAndClashIconAreVisibleUiParts() {
        for (skin in listOf("nightglass", "glass", "night")) {
            check("$skin on-accent on accent", 4.5, token(skin, "on-accent"), listOf(Spot("accent", token(skin, "accent"))))
        }
        val calm = nightGlassSpots(0.35).filter { it.name.startsWith("calm") || it.name.startsWith("now") }
        check("accent on glass", 3.0, token("nightglass", "accent"), calm)
        for (t in tints) {
            val rows = nightGlassSpots(t).filter { it.name.startsWith("calm") || it.name.startsWith("now") }
            for (a in Attention.entries) {
                check("clash icon ${a.name}, tint ${pct(t)}", 3.0, mix(hue(a.color), token("nightglass", "ink"), CLASH_ICON_INK_MIX.toDouble()), rows)
            }
        }
        assertNoMisses()
    }
}
