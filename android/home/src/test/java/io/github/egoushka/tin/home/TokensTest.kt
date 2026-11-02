package io.github.egoushka.tin.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.theme.Lucide
import io.github.egoushka.tin.home.theme.Ng
import io.github.egoushka.tin.home.theme.NgType
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** H4: the Compose theme equals docs/design/night-glass, token for token. */
class TokensTest {
    private val design = File(System.getProperty("tin.design") ?: "../../docs/design/night-glass")
    private val tokens = Json.parseToJsonElement(File(design, "tokens.json").readText()).jsonObject

    private fun parse(css: String): Color {
        val s = css.trim()
        if (s.startsWith("#")) return Color(("FF" + s.drop(1)).toLong(16))
        val m = Regex("""rgb\((\d+) (\d+) (\d+) / ([\d.]+)\)""").matchEntire(s) ?: error("unknown colour $s")
        val (r, g, b, a) = m.destructured
        return Color(r.toInt(), g.toInt(), b.toInt(), (a.toFloat() * 255).roundToInt())
    }

    @Test
    fun everyNightGlassColourMatches() {
        val list = tokens["color"]!!.jsonObject["tokens"]!!.jsonArray
        val names = mutableSetOf<String>()
        for (t in list) {
            val name = t.jsonObject["name"]!!.jsonPrimitive.content
            val value = t.jsonObject["value"]!!.jsonObject["nightglass"]?.jsonPrimitive?.content ?: continue
            names += name
            val want = parse(value)
            val got = Ng.byName[name] ?: error("Ng has no $name")
            for ((w, g) in listOf(want.red to got.red, want.green to got.green, want.blue to got.blue, want.alpha to got.alpha)) {
                assertTrue("$name: $want != $got", abs(w - g) <= 1f / 255f + 1e-4f)
            }
        }
        assertEquals("Ng.byName and tokens.json differ", names, Ng.byName.keys)
    }

    @Test
    fun typeStylesMatch() {
        val group = tokens["type"]!!.jsonObject["groups"]!!.jsonArray.map { it.jsonObject }
            .first { it["name"]!!.jsonPrimitive.content == "Night glass" }
        val ours = mapOf("ng-clock" to NgType.clock, "ng-title" to NgType.title, "ng-body" to NgType.body, "ng-label" to NgType.label)
        for (s in group["styles"]!!.jsonArray.map { it.jsonObject }) {
            val name = s["name"]!!.jsonPrimitive.content
            val style: TextStyle = ours[name] ?: error("no style for $name")
            assertEquals(name, px(s, "fontSize"), style.fontSize.value)
            assertEquals(name, px(s, "lineHeight"), style.lineHeight.value)
            assertEquals(name, s["fontWeight"]!!.jsonPrimitive.content.toInt(), style.fontWeight!!.weight)
            s["letterSpacing"]?.let { assertEquals(name, it.jsonPrimitive.content.removeSuffix("em").toFloat(), style.letterSpacing.value, 1e-4f) }
        }
        // The figure size (44) lives in the README, not tokens.json; five sizes in all.
        assertEquals(listOf(76f, 44f, 15f, 13f, 11f), NgType.all.map { it.fontSize.value })
        NgType.all.forEach { assertEquals("tnum", it.fontFeatureSettings) }
    }

    @Test
    fun sizesAndRadiiMatch() {
        fun tok(group: String, name: String) = tokens[group]!!.jsonObject["tokens"]!!.jsonArray.map { it.jsonObject }
            .first { it["name"]!!.jsonPrimitive.content == name }["value"]!!.jsonPrimitive.content.removeSuffix("px").toFloat()
        assertEquals(tok("size", "size-target"), Dimen.target.value)
        assertEquals(tok("size", "size-icon"), Dimen.tile.value)
        assertEquals(tok("size", "size-search"), Dimen.tile.value)
        assertEquals(tok("size", "size-glyph"), Dimen.glyph.value)
        assertEquals(tok("size", "stroke-glyph"), Dimen.STROKE)
        assertEquals(tok("radius", "radius-card"), Dimen.radiusCard.value)
        assertEquals(tok("radius", "radius-row"), Dimen.radiusRow.value)
        assertEquals(tok("radius", "radius-small"), Dimen.radiusSmall.value)
        assertEquals(tok("radius", "radius-phone"), Dimen.radiusPhone.value)
        listOf(1 to Dimen.space1, 2 to Dimen.space2, 3 to Dimen.space3, 4 to Dimen.space4, 6 to Dimen.space6).forEach { (n, d) ->
            assertEquals(tok("spacing", "space-$n"), d.value)
        }
    }

    @Test
    fun lucideBodiesAreTheCommittedOnes() {
        val icons = Json.parseToJsonElement(File(design, "lucide-icons.json").readText()).jsonObject
        for ((name, body) in Lucide.bodies) assertEquals(name, icons[name]!!.jsonPrimitive.content, body)
        Lucide.bodies.values.forEach { assertTrue(Lucide.toPathData(it).isNotEmpty()) }
    }

    private fun px(s: JsonObject, k: String) = s[k]!!.jsonPrimitive.content.removeSuffix("px").toFloat()
}
