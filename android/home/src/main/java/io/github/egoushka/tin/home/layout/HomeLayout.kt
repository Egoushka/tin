package io.github.egoushka.tin.home.layout

enum class CardId(val code: String) {
    NeedsYou("needs"), Today("today"), Glance("glance"), Music("music"), Alarm("alarm"), Homelab("homelab");

    companion object {
        fun of(code: String): CardId? = entries.firstOrNull { it.code == code }
    }
}

/** One cell of the card stack: a built-in card or a hosted widget. */
sealed interface Slot {
    val code: String

    data class Card(val id: CardId) : Slot {
        override val code get() = "card:${id.code}"
    }

    data class Widget(val id: Int) : Slot {
        override val code get() = "widget:$id"
    }

    companion object {
        fun parse(code: String): Slot? = when {
            code.startsWith("card:") -> CardId.of(code.removePrefix("card:"))?.let(::Card)
            code.startsWith("widget:") -> code.removePrefix("widget:").toIntOrNull()?.takeIf { it >= 0 }?.let(::Widget)
            else -> null
        }
    }
}

/**
 * Which slots the stack shows and in what order. [order] holds every slot, [hidden] the cards that are off;
 * a hidden card keeps its place so showing it again puts it back. [rows] is a widget's height in grid cells.
 */
data class HomeLayout(
    val order: List<Slot> = CardId.entries.map { Slot.Card(it) },
    val hidden: Set<Slot> = emptySet(),
    val rows: Map<Int, Int> = emptyMap(),
) {
    val visible: List<Slot> get() = order.filter { it !in hidden }
    val hiddenSlots: List<Slot> get() = order.filter { it in hidden }
    val widgetIds: Set<Int> get() = order.filterIsInstance<Slot.Widget>().map { it.id }.toSet()

    /** Swaps [slot] with its nearest visible neighbour [delta] places away; a no-op at the ends. */
    fun move(slot: Slot, delta: Int): HomeLayout {
        val shown = visible
        val from = shown.indexOf(slot)
        val to = from + delta
        if (from < 0 || to !in shown.indices) return this
        val a = order.indexOf(shown[from])
        val b = order.indexOf(shown[to])
        val next = order.toMutableList()
        next[a] = shown[to]
        next[b] = shown[from]
        return copy(order = next)
    }

    /** Only cards hide; a widget is removed instead. */
    fun hide(slot: Slot): HomeLayout = if (slot is Slot.Card && slot in order) copy(hidden = hidden + slot) else this

    fun show(slot: Slot): HomeLayout = copy(hidden = hidden - slot)

    fun addWidget(id: Int, rows: Int): HomeLayout =
        if (Slot.Widget(id) in order) this else copy(order = order + Slot.Widget(id), rows = this.rows + (id to rows.coerceIn(1, MAX_ROWS)))

    fun removeWidget(id: Int): HomeLayout =
        copy(order = order - Slot.Widget(id), rows = rows - id)

    fun setRows(id: Int, rows: Int): HomeLayout =
        if (id in widgetIds) copy(rows = this.rows + (id to rows.coerceIn(1, MAX_ROWS))) else this

    /** Drops widgets the host no longer knows. */
    fun reconcile(live: Set<Int>): HomeLayout = widgetIds.filter { it !in live }.fold(this) { l, id -> l.removeWidget(id) }

    fun encodeOrder(): String = order.joinToString(",") { it.code }
    fun encodeHidden(): String = hidden.joinToString(",") { it.code }
    fun encodeRows(): String = rows.entries.joinToString(",") { "${it.key}:${it.value}" }

    companion object {
        /** Cells are 70 dp minus 30 dp of gap, the stock launcher rule providers size themselves against. */
        const val MAX_ROWS = 8

        fun heightDp(rows: Int): Int = 70 * rows - 30

        /** The smallest number of rows that is at least [dp] tall. */
        fun rowsFor(dp: Int): Int = ((dp + 30 + 69) / 70).coerceIn(1, MAX_ROWS)

        /** The rows a provider can be shown in: from its smallest size up when it resizes vertically, else exactly its own. */
        fun rowRange(minDp: Int, minResizeDp: Int, resizesVertically: Boolean): IntRange {
            val least = rowsFor(if (resizesVertically && minResizeDp > 0) minResizeDp else minDp)
            return if (resizesVertically) least..MAX_ROWS else least..least
        }

        /** Tolerant of anything a past or future release wrote: unknown, repeated and malformed entries are dropped, a missing card is appended. */
        fun decode(order: String?, hidden: String?, rows: String?): HomeLayout {
            val slots = LinkedHashSet<Slot>()
            order.orEmpty().split(',').mapNotNull { Slot.parse(it.trim()) }.forEach(slots::add)
            CardId.entries.map { Slot.Card(it) }.forEach(slots::add)
            val off = hidden.orEmpty().split(',').mapNotNull { Slot.parse(it.trim()) }.filterTo(mutableSetOf()) { it is Slot.Card }
            val ids = slots.filterIsInstance<Slot.Widget>().map { it.id }.toSet()
            val heights = rows.orEmpty().split(',').mapNotNull { pair ->
                val (id, n) = pair.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                val key = id.trim().toIntOrNull()?.takeIf { it in ids } ?: return@mapNotNull null
                val value = n.trim().toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                key to value.coerceAtMost(MAX_ROWS)
            }.toMap()
            return HomeLayout(slots.toList(), off, heights)
        }
    }
}
