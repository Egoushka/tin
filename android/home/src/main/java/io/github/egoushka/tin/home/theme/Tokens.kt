package io.github.egoushka.tin.home.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.egoushka.tin.home.R

/** The `nightglass` column of docs/design/night-glass/tokens.json, one to one. TokensTest guards the mapping. */
object Ng {
    val bg = Color(0xFF07070B)
    val glow1 = Color(0xFF7C5CFF)
    val glow2 = Color(0xFF00E0C7)
    val glow3 = Color(0xFF2A1F6E)
    val surface = Color(0xFF14141D)
    val surface2 = Color(255, 255, 255, (0.08f * 255).toInt())
    val edge = Color(255, 255, 255, (0.12f * 255).toInt())
    val edgeHi = Color(255, 255, 255, (0.28f * 255).toInt())
    val well = Color(0, 0, 0, (0.35f * 255).toInt())
    val track = Color(255, 255, 255, (0.14f * 255).toInt())
    val ink = Color(0xFFF2F2F7)
    val ink2 = Color(0xFFB8B8C8)
    val accent = Color(0xFFF2F2F7)
    val onAccent = Color(0xFF0B0B12)
    val signal = Color(0xFF5EF0DC)
    val good = Color(0xFF4EE59A)
    val warn = Color(0xFFFFC65C)
    val bad = Color(0xFFFF6B78)

    /** Every token by its tokens.json name, for the test. */
    val byName: Map<String, Color> = mapOf(
        "bg" to bg, "glow-1" to glow1, "glow-2" to glow2, "glow-3" to glow3, "surface" to surface,
        "surface-2" to surface2, "edge" to edge, "edge-hi" to edgeHi, "well" to well, "track" to track,
        "ink" to ink, "ink-2" to ink2, "accent" to accent, "on-accent" to onAccent, "signal" to signal,
        "good" to good, "warn" to warn, "bad" to bad,
    )
}

/** The attention colour (`glow-1`): only hues that keep ink-2 at 4.5:1 on a lit card. Green is excluded. */
enum class Attention(val color: Color) {
    Violet(Color(0xFF7C5CFF)), Blue(Color(0xFF4C8DFF)), Rose(Color(0xFFFF5C93)), Amber(Color(0xFFFF9F43)),
}

/** Six constants; every size on Home derives from them. */
object Dimen {
    val grid = 4.dp
    val target = 48.dp
    val curve = 28.dp
    const val STROKE = 1.6f

    val space1 = grid
    val space2 = grid * 2
    val space3 = grid * 3
    val space4 = grid * 4
    val space6 = grid * 6

    val gutter = space4
    val tile = target + space2          // 56: app tile, search bar
    val radiusCard = tile / 2           // 28 = curve
    val radiusRow = radiusCard - space3 // 16, concentric inside a 12 dp padded card
    val radiusSmall = space2
    val radiusPhone = 44.dp
    val glyph = 20.dp
    val glyphSmall = 16.dp
    val glyphTile = 24.dp
    val backEdge = 24.dp                // Android's back gesture owns the side edges
}

/** The five type sizes; nothing else is allowed. Inter variable (OFL, assets/licenses), bundled whole: Yehor, 2026-11-02 (spec, H10). */
@OptIn(ExperimentalTextApi::class)
object NgType {
    // 450 and 550 exist only on the variable axis, so each weight pins `wght` explicitly.
    private val family = FontFamily(
        listOf(200, 450, 550, 600).map {
            Font(R.font.inter, FontWeight(it), variationSettings = FontVariation.Settings(FontVariation.weight(it)))
        },
    )
    private const val TNUM = "tnum"

    val clock = TextStyle(fontFamily = family, fontSize = 76.sp, lineHeight = 72.sp, fontWeight = FontWeight(200),
        letterSpacing = (-0.04).em, fontFeatureSettings = TNUM, color = Ng.ink)
    val figure = TextStyle(fontFamily = family, fontSize = 44.sp, lineHeight = 44.sp, fontWeight = FontWeight(200),
        fontFeatureSettings = TNUM, color = Ng.ink)
    val title = TextStyle(fontFamily = family, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight(550),
        fontFeatureSettings = TNUM, color = Ng.ink)
    val body = TextStyle(fontFamily = family, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight(450),
        fontFeatureSettings = TNUM, color = Ng.ink2)
    val label = TextStyle(fontFamily = family, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight(600),
        letterSpacing = 0.07.em, fontFeatureSettings = TNUM, color = Ng.ink2)

    val all = listOf(clock, figure, title, body, label)
}

/** Material 3 motion durations (ms) for the light ladder; starting values to calibrate on the phone. */
object Motion {
    const val NEEDS_YOU_IN = 300   // medium2
    const val NEEDS_YOU_OUT = 500  // long2
    const val NOW_IN = 300         // medium2
    const val BROKEN_IN = 200      // short4
    const val STALE_IN = 1000      // extraLong4
    const val FRESH_IN = 100       // short2
    const val STALE_ALPHA = 0.55f
    const val FOCUS_DIM = 0.4f

    /** Reduced motion (animator scale 0) makes every transition instant. */
    fun duration(ms: Int, animatorScale: Float): Int = if (animatorScale == 0f) 0 else ms
}
