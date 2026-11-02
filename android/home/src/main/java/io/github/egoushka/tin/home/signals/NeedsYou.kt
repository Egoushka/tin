package io.github.egoushka.tin.home.signals

/** One row of the approvals provider's `items` table. [held] marks a write the approvals app is holding for a decision. */
data class NeedsItem(val id: String, val title: String, val source: String, val createdAt: Long, val held: Boolean, val deepLink: String)

/** What the card shows: the counts the provider reports, plus the oldest items first so nothing waits out of sight. */
data class NeedsYou(val open: Int, val held: Int, val rows: List<NeedsItem>, val more: Int) {
    val lit: Boolean get() = open > 0

    companion object {
        const val MAX_ROWS = 2
        val Empty = NeedsYou(0, 0, emptyList(), 0)

        fun of(items: List<NeedsItem>, open: Int, held: Int): NeedsYou {
            val sorted = items.sortedBy { it.createdAt }
            return NeedsYou(open, held, sorted.take(MAX_ROWS), (sorted.size - MAX_ROWS).coerceAtLeast(0))
        }
    }
}
