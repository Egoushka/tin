package io.github.egoushka.tin.home.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Lucide 0.460.0 (ISC) icon bodies, copied from docs/design/night-glass/lucide-icons.json (LucideTest checks the copy).
 * The stroke never scales: an icon drawn at [sizeDp] gets a viewport stroke of strokeDp * 24 / sizeDp.
 */
object Lucide {
    val bodies: Map<String, String> = mapOf(
        "search" to """<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>""",
        "mic" to """<path d="M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" x2="12" y1="19" y2="22"/>""",
        "phone" to """<path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"/>""",
        "msg" to """<path d="M7.9 20A9 9 0 1 0 4 16.1L2 22Z"/>""",
        "camera" to """<path d="M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z"/><circle cx="12" cy="13" r="3"/>""",
        "globe" to """<circle cx="12" cy="12" r="10"/><path d="M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20"/><path d="M2 12h20"/>""",
        "map" to """<path d="M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0"/><circle cx="12" cy="10" r="3"/>""",
        "sliders" to """<line x1="21" x2="14" y1="4" y2="4"/><line x1="10" x2="3" y1="4" y2="4"/><line x1="21" x2="12" y1="12" y2="12"/><line x1="8" x2="3" y1="12" y2="12"/><line x1="21" x2="16" y1="20" y2="20"/><line x1="12" x2="3" y1="20" y2="20"/><line x1="14" x2="14" y1="2" y2="6"/><line x1="8" x2="8" y1="10" y2="14"/><line x1="16" x2="16" y1="18" y2="22"/>""",
        "torch" to """<path d="M18 6c0 2-2 2-2 4v10a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2V10c0-2-2-2-2-4V2h12z"/><line x1="6" x2="18" y1="6" y2="6"/><line x1="12" x2="12" y1="12" y2="12"/>""",
        "x" to """<path d="M18 6 6 18"/><path d="m6 6 12 12"/>""",
        "grid" to """<rect width="7" height="7" x="3" y="3" rx="1"/><rect width="7" height="7" x="14" y="3" rx="1"/><rect width="7" height="7" x="14" y="14" rx="1"/><rect width="7" height="7" x="3" y="14" rx="1"/>""",
        "check" to """<path d="M20 6 9 17l-5-5"/>""",
    )

    private data class Key(val name: String, val sizeDp: Float, val strokeDp: Float, val color: Color)
    private val cache = HashMap<Key, ImageVector>()

    fun icon(name: String, sizeDp: Float, strokeDp: Float, color: Color = Ng.ink): ImageVector =
        cache.getOrPut(Key(name, sizeDp, strokeDp, color)) {
        val stroke = strokeDp * 24f / sizeDp
        ImageVector.Builder(name, sizeDp.dp, sizeDp.dp, 24f, 24f).apply {
            for (d in toPathData(bodies.getValue(name))) {
                addPath(addPathNodes(d), stroke = SolidColor(color), strokeLineWidth = stroke,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
        }.build()
    }

    private val element = Regex("""<(\w+)([^>]*)/>""")
    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    /** SVG elements (path, circle, line, rect, polyline) as path data strings. */
    fun toPathData(body: String): List<String> = element.findAll(body).map { m ->
        val a = attr.findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
        fun f(k: String) = a[k]?.toFloat() ?: 0f
        when (m.groupValues[1]) {
            "path" -> a.getValue("d")
            "circle" -> {
                val (cx, cy, r) = Triple(f("cx"), f("cy"), f("r"))
                "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0Z"
            }
            "line" -> "M${f("x1")},${f("y1")}L${f("x2")},${f("y2")}"
            "polyline" -> "M" + a.getValue("points").trim().split(Regex("\\s+")).joinToString("L")
            "rect" -> {
                val (x, y, w, h) = listOf(f("x"), f("y"), f("width"), f("height"))
                val r = f("rx")
                "M${x + r},${y}h${w - 2 * r}a$r,$r 0 0 1 $r,${r}v${h - 2 * r}a$r,$r 0 0 1 ${-r},${r}" +
                    "h${-(w - 2 * r)}a$r,$r 0 0 1 ${-r},${-r}v${-(h - 2 * r)}a$r,$r 0 0 1 $r,${-r}Z"
            }
            else -> error("unsupported SVG element ${m.groupValues[1]}")
        }
    }.toList()
}
