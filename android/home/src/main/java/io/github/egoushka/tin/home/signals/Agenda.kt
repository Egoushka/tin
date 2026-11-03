package io.github.egoushka.tin.home.signals

/** One calendar instance as Home needs it; all-day and declined events never get here. */
data class Event(val id: Long, val title: String, val beginMs: Long, val endMs: Long, val location: String?)

/** A row of the Today card: [current] is teal (now), [clash] is warn. */
data class AgendaItem(val event: Event, val current: Boolean, val clash: Boolean)

/** The rest of today as shown: [items] up to [MAX_ROWS], [more] counts the ones that did not fit. */
data class Agenda(val items: List<AgendaItem>, val more: Int) {
    val hasCurrent: Boolean get() = items.any { it.current }
    val hasClash: Boolean get() = items.any { it.clash }

    companion object {
        const val MAX_ROWS = 3
        val Empty = Agenda(emptyList(), 0)

        /** Events that ended are dropped; two that overlap are both marked, wherever the pair sits in the list. */
        fun of(events: List<Event>, nowMs: Long): Agenda {
            val rows = all(events, nowMs)
            return Agenda(rows.take(MAX_ROWS), (rows.size - MAX_ROWS).coerceAtLeast(0))
        }

        /** The same rows without the card's cap: everything still to come, for the Today sheet. */
        fun all(events: List<Event>, nowMs: Long): List<AgendaItem> {
            val live = events.filter { it.endMs > nowMs }.sortedWith(compareBy({ it.beginMs }, { it.endMs }))
            return live.map { e ->
                AgendaItem(
                    e,
                    current = e.beginMs <= nowMs,
                    clash = live.any { o -> o !== e && o.beginMs < e.endMs && e.beginMs < o.endMs },
                )
            }
        }
    }
}
