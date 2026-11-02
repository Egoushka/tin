package io.github.egoushka.tin.home

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.theme.GlassPaint
import io.github.egoushka.tin.home.theme.LightMotion
import io.github.egoushka.tin.home.theme.Motion
import io.github.egoushka.tin.home.theme.Ng
import io.github.egoushka.tin.home.theme.NgType
import io.github.egoushka.tin.home.theme.colorMix
import io.github.egoushka.tin.home.theme.lerpColor
import io.github.egoushka.tin.home.tune.Tune
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C1-C5: the constants Home paints with are the numbers of the Night glass block of bundle.css, parsed from the file, so a
 * drifting constant fails here instead of being found by eye. Lengths are CSS px, which are dp on the 412 dp design phone.
 */
class NightGlassCssTest {
    private val design = File(System.getProperty("tin.design") ?: "../../docs/design/night-glass")
    private val css = File(design, "bundle.css").readText()

    /** From the Night glass block to the end: its rules come after, and win over, the shared ones above. */
    private val night = css.substring(css.indexOf("Night glass (v4)").also { assertTrue("no Night glass block", it > 0) })

    /** The declarations of the first `selector { ... }` in the Night glass block. */
    private fun rule(selector: String): String {
        val at = night.indexOf("$selector {")
        assertTrue("no rule for $selector", at >= 0)
        return night.substring(at + selector.length + 2).substringBefore("}")
    }

    private fun groups(re: String, text: String): List<String> =
        Regex(re).find(text)?.groupValues?.drop(1) ?: error("/$re/ does not match: $text")

    private fun f(s: String) = s.toFloat()
    private fun pct(s: String) = s.toFloat() / 100f

    @Test
    fun cardShadowIsTheWidgetShadow() {
        val v = groups("""--w-shadow: inset 0 (\d+)px 0 var\(--edge-hi\), 0 (\d+)px (\d+)px (-?\d+)px rgb\(0 0 0 / ([\d.]+)\)""", night)
        assertEquals(f(v[0]), GlassPaint.EDGE_LIT_DY, 0f)
        assertEquals(f(v[1]), GlassPaint.DROP_DY, 0f)
        assertEquals(f(v[2]), GlassPaint.DROP_BLUR, 0f)
        assertEquals(f(v[3]), GlassPaint.DROP_SPREAD, 0f)
        assertEquals(f(v[4]), GlassPaint.DROP_ALPHA, 0f)
    }

    @Test
    fun needsYouLightIsTheHotCard() {
        val hot = rule("""[data-theme="nightglass"] .w.hot""")
        val g = groups("""radial-gradient\((\d+)% (\d+)% at (\d+)% (\d+)%, color-mix\(in srgb, var\(--glow-1\) (\d+)%, transparent\) 0, transparent (\d+)%\)""", hot)
        assertEquals(pct(g[0]), GlassPaint.NEEDS_RX, 1e-6f)
        assertEquals(pct(g[1]), GlassPaint.NEEDS_RY, 1e-6f)
        assertEquals(pct(g[2]), GlassPaint.NEEDS_CX, 1e-6f)
        assertEquals(pct(g[3]), GlassPaint.NEEDS_CY, 1e-6f)
        assertEquals(pct(g[4]), GlassPaint.NEEDS_ALPHA, 1e-6f)
        assertEquals(pct(g[5]), GlassPaint.NEEDS_FADE, 1e-6f)
        assertEquals(pct(groups("""border-color: color-mix\(in srgb, var\(--glow-1\) (\d+)%, var\(--edge\)\)""", hot)[0]),
            GlassPaint.NEEDS_BORDER, 1e-6f)
        val s = groups("""box-shadow: inset 0 1px 0 color-mix\(in srgb, var\(--glow-1\) (\d+)%, var\(--edge-hi\)\), 0 0 (\d+)px (-?\d+)px var\(--glow-1\)""", hot)
        assertEquals(pct(s[0]), GlassPaint.NEEDS_LINE, 1e-6f)
        assertEquals(f(s[1]), GlassPaint.HALO_BLUR, 0f)
        assertEquals(f(s[2]), GlassPaint.HALO_SPREAD, 0f)
    }

    @Test
    fun brokenLightIsTheBadMiniCard() {
        val bad = rule("""[data-theme="nightglass"] .w.mini.bad""")
        val g = groups("""radial-gradient\((\d+)% (\d+)% at (\d+)% (\d+)%, color-mix\(in srgb, var\(--bad\) (\d+)%, transparent\) 0, transparent (\d+)%\)""", bad)
        assertEquals(pct(g[0]), GlassPaint.BROKEN_RX, 1e-6f)
        assertEquals(pct(g[1]), GlassPaint.BROKEN_RY, 1e-6f)
        assertEquals(pct(g[2]), GlassPaint.BROKEN_CX, 1e-6f)
        assertEquals(pct(g[3]), GlassPaint.BROKEN_CY, 1e-6f)
        assertEquals(pct(g[4]), GlassPaint.BROKEN_ALPHA, 1e-6f)
        assertEquals(pct(g[5]), GlassPaint.BROKEN_FADE, 1e-6f)
        assertEquals(pct(groups("""border-color: color-mix\(in srgb, var\(--bad\) (\d+)%, var\(--edge\)\)""", bad)[0]),
            GlassPaint.BROKEN_BORDER, 1e-6f)
    }

    @Test
    fun nowRowIsTheSignalFillAndRing() {
        val now = rule("""[data-theme="nightglass"] .ag-row.now""")
        assertEquals(pct(groups("""background: color-mix\(in srgb, var\(--signal\) (\d+)%, transparent\)""", now)[0]),
            GlassPaint.NOW_FILL, 1e-6f)
        val ring = groups("""box-shadow: inset 0 0 0 (\d+)px color-mix\(in srgb, var\(--signal\) (\d+)%, transparent\)""", now)
        assertEquals(f(ring[0]), GlassPaint.NOW_RING_WIDTH, 0f)
        assertEquals(pct(ring[1]), GlassPaint.NOW_RING, 1e-6f)
    }

    @Test
    fun theEdgeIsOneDpAndTheStrokeIsThePenWidth() {
        assertEquals("1px", groups("""--w-edge: (\d+px) solid var\(--edge\)""", night)[0])
        assertEquals(1f, GlassPaint.BORDER_WIDTH, 0f)
        assertEquals(f(groups("""--k-stroke: ([\d.]+)""", night)[0]), Dimen.STROKE, 0f)
    }

    @Test
    fun cardPaddingIsTheKnobAndOnTheGrid() {
        val pad = f(groups("""--k-pad: (\d+)px""", night)[0])
        assertEquals(pad, Tune().padDp, 0f)
        assertEquals(0f, pad % (Dimen.grid.value), 0f)
        assertEquals(Dimen.space3.value, pad, 0f)
        // concentric corners: the row radius is the card radius less that padding
        assertEquals(Dimen.radiusCard.value - pad, Dimen.radiusRow.value, 0f)
        assertEquals(f(groups("""--r-in: (\d+)px""", night)[0]), Dimen.radiusRow.value, 0f)
        assertEquals(f(groups("""--r-w: (\d+)px""", night)[0]), Dimen.radiusCard.value, 0f)
    }

    @Test
    fun metaIsTheTrailingFigureStyle() {
        val m = groups("""--t-meta: (\d+) (\d+)px/(\d+)px""", css)
        assertEquals(m[0].toInt(), NgType.meta.fontWeight!!.weight)
        assertEquals(f(m[1]), NgType.meta.fontSize.value, 0f)
        assertEquals(f(m[2]), NgType.meta.lineHeight.value, 0f)
        assertEquals("tnum", NgType.meta.fontFeatureSettings)
        assertEquals(Ng.ink2, NgType.meta.color)
    }

    @Test
    fun stillFiveTypeSizes() {
        assertEquals(listOf(76f, 44f, 15f, 13f, 11f), NgType.all.map { it.fontSize.value })
    }

    @Test
    fun pressIsTheIconTileAndMicRule() {
        assertEquals(f(groups("""\.ic:active i, \.ic\.is-press i \{ transform: scale\(([\d.]+)\)""", css)[0]), Motion.PRESS_SCALE, 0f)
        assertEquals(f(groups("""\.sb-mic:active, \.sb-mic\.is-press \{ transform: scale\(([\d.]+)\)""", css)[0]), Motion.PRESS_SCALE, 0f)
        assertEquals(groups("""\.ic i \{[^}]*?transition: transform (\d+)ms var\(--spring\)""", css)[0].toInt(), Motion.PRESS_MS)
        assertEquals(groups("""\.sb-mic \{[^}]*?transition: transform (\d+)ms var\(--spring\)""", css)[0].toInt(), Motion.PRESS_MS)
        assertEquals(Motion.PRESS_MS, LightMotion.press.ms)
        val pressed = rule("""[data-theme="nightglass"] .ic:active i, [data-theme="nightglass"] .ic.is-press i""")
        assertTrue(pressed.contains("background: var(--surface-2)") && pressed.contains("border-color: var(--edge-hi)"))
    }

    @Test
    fun springIsTheStandardCurve() {
        val c = groups("""--spring: cubic-bezier\(([\d.]+), ([\d.]+), ([\d.]+), ([\d.]+)\)""", css).map(::f)
        val spring = CubicBezierEasing(c[0], c[1], c[2], c[3])
        for (x in listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f)) assertEquals(spring.transform(x), Motion.Standard.transform(x), 1e-4f)
    }

    @Test
    fun focusAndStaleAreTheCssOpacities() {
        val focus = groups("""\.ph\.focus \.w:not\(\.hot\):not\(\.bad\):not\(\.ag\) \{ opacity: ([\d.]+)""", css)
        assertEquals(f(focus[0]), Motion.FOCUS_DIM, 0f)
        assertEquals(groups("""\.ph \.w \{ transition: opacity (\d+)ms var\(--spring\)""", css)[0].toInt(), Motion.FOCUS_MS)
        assertEquals(f(groups("""opacity: ([\d.]+)""", rule("""[data-theme="nightglass"] .stale"""))[0]), Motion.STALE_ALPHA, 0f)
    }

    /** Compose stores sRGB colours at 8 bits a channel, so a blend is right to within one step. */
    private val EIGHT_BIT = 1f / 255f + 1e-4f

    private fun assertColor(want: Color, got: Color) {
        for ((w, g) in listOf(want.red to got.red, want.green to got.green, want.blue to got.blue, want.alpha to got.alpha)) {
            assertEquals("$want != $got", w, g, EIGHT_BIT)
        }
    }

    @Test
    fun colourMixIsPremultipliedLikeCss() {
        val red = Color(1f, 0f, 0f, 1f)
        val faded = colorMix(red, 0.3f, Color.Transparent)
        assertEquals(0.3f, faded.alpha, EIGHT_BIT)
        assertEquals("a transparent partner must not darken the hue", 1f, faded.red, EIGHT_BIT)
        assertEquals(0f, faded.green, EIGHT_BIT)
        assertColor(red, colorMix(red, 1f, Ng.edge))
        assertColor(Ng.edge, lerpColor(Ng.edge, Ng.edgeHi, 0f))
        assertColor(Ng.edgeHi, lerpColor(Ng.edge, Ng.edgeHi, 1f))
        assertEquals((Ng.edge.alpha + Ng.edgeHi.alpha) / 2f, lerpColor(Ng.edge, Ng.edgeHi, 0.5f).alpha, EIGHT_BIT)
    }
}
