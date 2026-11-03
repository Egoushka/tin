package io.github.egoushka.tin.home.pages

import io.github.egoushka.tin.home.layout.HomeLayout

/** One thing on an extra page. [id] is its identity on a page and survives a move. */
sealed interface PageItem {
    val id: String

    data class App(val key: String) : PageItem {
        override val id get() = "app:$key"
    }

    /** [label] is what the tile says when the launcher cannot resolve the shortcut any more. */
    data class Shortcut(val pkg: String, val shortcutId: String, val user: Long, val label: String) : PageItem {
        override val id get() = "shortcut:$user/$pkg/$shortcutId"
    }

    data class Widget(val appWidgetId: Int, val rows: Int) : PageItem {
        override val id get() = "widget:$appWidgetId"
    }
}

/** What one row of a page shows: up to [Pages.COLUMNS] tiles, or one widget across the whole width. */
sealed interface FlowRow {
    data class Tiles(val items: List<PageItem>) : FlowRow
    data class Wide(val widget: PageItem.Widget) : FlowRow
}

/**
 * The pages beside the main one: each an ordered list of [PageItem]s. A page is addressed by its index among the
 * extra pages, so 0 is the page just right of the main one. Every change returns the same value when it does nothing.
 */
data class Pages(val pages: List<List<PageItem>> = emptyList()) {
    val count: Int get() = pages.size

    val widgetIds: Set<Int> get() = pages.flatten().filterIsInstance<PageItem.Widget>().map { it.appWidgetId }.toSet()

    fun addPage(): Pages = if (count >= MAX_EXTRA) this else Pages(pages + listOf(emptyList()))

    fun removePage(page: Int): Pages = if (page in pages.indices) Pages(pages.filterIndexed { i, _ -> i != page }) else this

    /** Appends [item] to [page]; a repeat of something already on that page, or a full page, changes nothing. */
    fun add(page: Int, item: PageItem): Pages {
        val items = pages.getOrNull(page) ?: return this
        if (items.any { it.id == item.id } || items.size >= MAX_ITEMS) return this
        return replace(page, items + item)
    }

    fun remove(page: Int, itemId: String): Pages {
        val items = pages.getOrNull(page) ?: return this
        return if (items.any { it.id == itemId }) replace(page, items.filter { it.id != itemId }) else this
    }

    /**
     * Takes [itemId] off [from] and puts it on [to] so that it ends up at [index] of that page's other items (clamped).
     * Nothing changes when either page or the item is missing, or when the target page already holds the same thing.
     */
    fun move(from: Int, itemId: String, to: Int, index: Int): Pages {
        val source = pages.getOrNull(from) ?: return this
        val target = pages.getOrNull(to) ?: return this
        val item = source.firstOrNull { it.id == itemId } ?: return this
        if (from != to && (target.any { it.id == itemId } || target.size >= MAX_ITEMS)) return this
        val rest = source.filter { it.id != itemId }
        val next = if (from == to) rest else target
        val at = index.coerceIn(0, next.size)
        val placed = next.toMutableList().apply { add(at, item) }
        val replaced = if (from == to) this else replace(from, rest)
        return replaced.replace(to, placed)
    }

    /** [move] to just before [before] on the target page, or to its end when that is null or no longer there. */
    fun moveBefore(from: Int, itemId: String, to: Int, before: String?): Pages {
        val others = pages.getOrNull(to)?.filter { it.id != itemId } ?: return this
        val at = before?.let { id -> others.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: others.size
        return move(from, itemId, to, at)
    }

    /** Moves [itemId] one place within its page; a no-op at the ends. */
    fun step(page: Int, itemId: String, delta: Int): Pages {
        val items = pages.getOrNull(page) ?: return this
        val at = items.indexOfFirst { it.id == itemId }
        if (at < 0 || at + delta !in items.indices) return this
        return move(page, itemId, page, at + delta)
    }

    fun setRows(appWidgetId: Int, rows: Int): Pages {
        val id = PageItem.Widget(appWidgetId, 1).id
        val at = pages.indexOfFirst { page -> page.any { it.id == id } }
        if (at < 0) return this
        val next = pages[at].map { if (it.id == id) PageItem.Widget(appWidgetId, rows.coerceIn(1, HomeLayout.MAX_ROWS)) else it }
        return replace(at, next)
    }

    /** The page that holds [itemId], if any (the first, when it sits on several). */
    fun pageOf(itemId: String): Int? = pages.indexOfFirst { page -> page.any { it.id == itemId } }.takeIf { it >= 0 }

    /** Every shortcut id placed for one app, over all pages; the launcher keeps exactly these pinned. */
    fun shortcutIds(pkg: String, user: Long): Set<String> =
        pages.flatten().filterIsInstance<PageItem.Shortcut>().filter { it.pkg == pkg && it.user == user }.map { it.shortcutId }.toSet()

    /** Drops widgets the host no longer knows. */
    fun reconcile(live: Set<Int>): Pages {
        val dead = widgetIds.filter { it !in live }.toSet()
        if (dead.isEmpty()) return this
        return Pages(pages.map { page -> page.filterNot { it is PageItem.Widget && it.appWidgetId in dead } })
    }

    private fun replace(page: Int, items: List<PageItem>) = Pages(pages.mapIndexed { i, old -> if (i == page) items else old })

    fun encode(): String = pages.joinToString("") { page -> page.joinToString(",") { Codec.encode(it) } + ";" }

    companion object {
        /** A starting value, not measured: six pages with the main one keep the dots small. */
        const val MAX_EXTRA = 5

        /** Four rows of four tiles and then some; also what a corrupt string may expand to. */
        const val MAX_ITEMS = 64

        const val COLUMNS = 4

        /** Tiles flow four to a row in order; a widget takes a row of its own and the tiles after it start a new one. */
        fun flow(items: List<PageItem>): List<FlowRow> {
            val rows = mutableListOf<FlowRow>()
            var run = mutableListOf<PageItem>()
            fun close() {
                if (run.isNotEmpty()) rows.add(FlowRow.Tiles(run))
                run = mutableListOf()
            }
            for (item in items) {
                if (item is PageItem.Widget) {
                    close()
                    rows.add(FlowRow.Wide(item))
                } else {
                    run.add(item)
                    if (run.size == COLUMNS) close()
                }
            }
            close()
            return rows
        }

        /** Tolerant of anything a past or future release wrote: a bad page or item is skipped, the rest is kept. */
        fun decode(raw: String?): Pages {
            if (raw.isNullOrEmpty()) return Pages()
            val parts = raw.split(';')
            val segments = if (parts.last().isEmpty()) parts.dropLast(1) else parts
            val pages = segments.take(MAX_EXTRA).map { segment ->
                val seen = HashSet<String>()
                segment.split(',').mapNotNull { Codec.decode(it) }.filter { seen.add(it.id) }.take(MAX_ITEMS)
            }
            return Pages(pages)
        }
    }

    /** `a|key`, `s|pkg|id|user|label`, `w|id|rows`; the separators inside a field are percent-escaped. */
    private object Codec {
        fun encode(item: PageItem): String = when (item) {
            is PageItem.App -> "a|${esc(item.key)}"
            is PageItem.Shortcut -> "s|${esc(item.pkg)}|${esc(item.shortcutId)}|${item.user}|${esc(item.label)}"
            is PageItem.Widget -> "w|${item.appWidgetId}|${item.rows}"
        }

        fun decode(token: String): PageItem? {
            val f = token.split('|')
            return when (f.firstOrNull()) {
                "a" -> if (f.size == 2 && f[1].isNotEmpty()) PageItem.App(unesc(f[1])) else null
                "s" -> {
                    if (f.size != 5) return null
                    val user = f[3].toLongOrNull() ?: return null
                    val pkg = unesc(f[1])
                    val id = unesc(f[2])
                    if (pkg.isEmpty() || id.isEmpty()) null else PageItem.Shortcut(pkg, id, user, unesc(f[4]))
                }
                "w" -> {
                    if (f.size != 3) return null
                    val id = f[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                    val rows = f[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
                    PageItem.Widget(id, rows.coerceAtMost(HomeLayout.MAX_ROWS))
                }
                else -> null
            }
        }

        private val specials = mapOf('%' to "%25", ',' to "%2C", ';' to "%3B", '|' to "%7C", '\n' to "%0A")

        private fun esc(s: String) = buildString { s.forEach { append(specials[it] ?: it) } }

        private fun unesc(s: String): String {
            if ('%' !in s) return s
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val code = if (s[i] == '%' && i + 3 <= s.length) specials.entries.firstOrNull { it.value == s.substring(i, i + 3) }?.key else null
                if (code != null) { out.append(code); i += 3 } else { out.append(s[i]); i++ }
            }
            return out.toString()
        }
    }
}
