package io.github.egoushka.tin.home.layout

import io.github.egoushka.tin.home.signals.Agenda
import io.github.egoushka.tin.home.signals.AgendaItem
import io.github.egoushka.tin.home.signals.Event
import io.github.egoushka.tin.home.signals.Homelab
import io.github.egoushka.tin.home.signals.NeedsYou

/** The tiles of page 0 below the glance row (row 0, fixed, not packed here). */
sealed interface Tile {
    object LeaveNow : Tile
    object NeedsYou : Tile
    object NextEvent : Tile
    object RestOfToday : Tile
    object Homelab : Tile
    object Music : Tile
    object Notifications : Tile
    data class Widget(val id: Int) : Tile
}

/**
 * Everything [arrange] reads, reduced to values so it needs no Android type. Times are epoch ms.
 * The sources keep no timestamps for these, so the caller passes them: [needsNewestMs] is the newest open item,
 * [faultSinceMs] when the current fault began, [musicPausedAtMs] when playback last stopped.
 */
data class BentoInputs(
    val events: List<Event> = emptyList(),
    val needs: NeedsYou = NeedsYou.Empty,
    val needsNewestMs: Long? = null,
    val homelab: Homelab = Homelab.Empty,
    val faultSinceMs: Long? = null,
    val musicPlaying: Boolean = false,
    val musicPausedAtMs: Long? = null,
    val notes: Int = 0,
    /** A widget's height in launcher cells, as [HomeLayout.rows] stores it. */
    val widgetRows: Map<Int, Int> = emptyMap(),
    val leadMin: Int = Bento.DEFAULT_LEAD_MIN,
)

/** A tile asked for at [w]×[h] cells; [lead] marks the one saturated tile. */
data class Sized(val tile: Tile, val w: Int, val h: Int, val lead: Boolean = false)

/** [w] and [h] are final, after stretching into gaps; [baseW] and [baseH] are what the sizing rules asked for. */
data class Placed(
    val tile: Tile,
    val col: Int,
    val row: Int,
    val w: Int,
    val h: Int,
    val baseW: Int,
    val baseH: Int,
    val lead: Boolean,
)

/** The leave-by tile: [late] once the leave time has passed, and [figureMin] counts to it, or to the start when late. */
data class LeaveNow(val event: Event, val leaveAtMs: Long, val late: Boolean, val figureMin: Int)

/** What the hold compares: a change that is new information bypasses the 5 minutes. */
data class Marks(
    val order: List<Slot>,
    val rows: Int,
    val widgetRows: Map<Int, Int>,
    val needsNewestMs: Long?,
    val needsHeld: Int,
    val broken: Set<String>,
    val faultSinceMs: Long?,
    val leave: Pair<Long, Boolean>?,
    val nextSoon: Boolean,
    val live: Set<Long>,
    val agenda: Set<Long>,
)

/**
 * [placed] are in order with absolute rows (the glance row is row 0, so they start at 1). [chip] says Notifications
 * collapsed into the line under the glance row. [leave], [next] and [rest] are always current; only the layout is held.
 */
data class Arrangement(
    val placed: List<Placed>,
    val lead: Tile?,
    val chip: Boolean,
    val scrolls: Boolean,
    val leave: LeaveNow?,
    val next: AgendaItem?,
    val rest: List<AgendaItem>,
    val madeAtMs: Long,
    val marks: Marks,
)

object Bento {
    const val COLUMNS = 4
    const val FIRST_ROW = 1
    const val DEFAULT_LEAD_MIN = 30
    const val MAX_REST_ROWS = 3
    const val MAX_WIDGET_ROWS = 4
    const val MIN_MS = 60_000L
    const val HOLD_MS = 5 * MIN_MS
    const val SOON_MS = 15 * MIN_MS
    const val FRESH_MS = 30 * MIN_MS
    const val MUSIC_PAUSED_MS = 10 * MIN_MS
    const val LEAVE_OPENS_MS = 45 * MIN_MS

    /** A widget is 4 wide; its height is its launcher cells scaled to the 94 dp grid row. */
    fun widgetGridRows(cells: Int): Int =
        ((HomeLayout.heightDp(cells) + 12 + 93) / 94).coerceIn(1, MAX_WIDGET_ROWS)

    private val NOT_A_PLACE = Regex("""[a-z]+://|www\.|\b(meet|zoom|teams|webex|online|call)\b""", RegexOption.IGNORE_CASE)

    fun isPlace(location: String?): Boolean = !location.isNullOrBlank() && !NOT_A_PLACE.containsMatchIn(location)

    /** The first place-event whose window is open: 45 min before the leave time, until it begins. */
    fun leaveNow(events: List<Event>, nowMs: Long, leadMin: Int): LeaveNow? =
        events.filter { isPlace(it.location) }.sortedBy { it.beginMs }.firstNotNullOfOrNull { e ->
            val leaveAt = e.beginMs - leadMin * MIN_MS
            if (nowMs < leaveAt - LEAVE_OPENS_MS || nowMs >= e.beginMs) return@firstNotNullOfOrNull null
            val late = nowMs > leaveAt
            LeaveNow(e, leaveAt, late, ceilMin((if (late) e.beginMs else leaveAt) - nowMs))
        }

    private fun ceilMin(ms: Long): Int = ((ms + MIN_MS - 1) / MIN_MS).toInt()
}

/**
 * Strictly in order: each tile takes the first free spot at or after the previous tile's origin, so nothing is pulled
 * forward. Free cells inside the used rows then go to the tile left of or above them (right first), which is the
 * last-in-row stretch.
 */
fun pack(items: List<Sized>, firstRow: Int = Bento.FIRST_ROW): List<Placed> {
    val cols = Bento.COLUMNS
    val busy = HashSet<Long>()
    fun key(c: Int, r: Int) = r.toLong() * cols + c
    fun free(c: Int, r: Int, w: Int, h: Int): Boolean {
        for (x in c until c + w) for (y in r until r + h) if (key(x, y) in busy) return false
        return true
    }
    fun take(c: Int, r: Int, w: Int, h: Int) {
        for (x in c until c + w) for (y in r until r + h) busy += key(x, y)
    }

    class Box(val item: Sized, val w0: Int, val h0: Int, val c: Int, val r: Int, var w: Int, var h: Int)

    val boxes = ArrayList<Box>()
    var from = 0
    for (item in items) {
        val w = item.w.coerceIn(1, cols)
        val h = item.h.coerceAtLeast(1)
        var i = from
        while (i % cols + w > cols || !free(i % cols, i / cols, w, h)) i++
        take(i % cols, i / cols, w, h)
        boxes += Box(item, w, h, i % cols, i / cols, w, h)
        from = i
    }
    val used = boxes.maxOfOrNull { it.r + it.h } ?: 0
    do {
        var changed = false
        for (b in boxes) {
            if (b.c + b.w < cols && free(b.c + b.w, b.r, 1, b.h)) {
                take(b.c + b.w, b.r, 1, b.h)
                b.w++
                changed = true
            } else if (b.r + b.h < used && free(b.c, b.r + b.h, b.w, 1)) {
                take(b.c, b.r + b.h, b.w, 1)
                b.h++
                changed = true
            }
        }
    } while (changed)
    return boxes.map { Placed(it.item.tile, it.c, it.r + firstRow, it.w, it.h, it.w0, it.h0, it.item.lead) }
}

/**
 * [rows] is the grid rows on page 0 including the glance row; [order] is the visible slots in the user's order.
 * [held] is the last result: its layout is kept for 5 minutes unless [Marks] shows new information.
 */
fun arrange(nowMs: Long, signals: BentoInputs, order: List<Slot>, rows: Int, held: Arrangement? = null): Arrangement {
    val fresh = build(nowMs, signals, order, rows)
    if (held == null || nowMs < held.madeAtMs || nowMs - held.madeAtMs >= Bento.HOLD_MS || bypass(held.marks, fresh.marks)) return fresh
    return fresh.copy(
        placed = held.placed,
        lead = held.lead,
        chip = held.chip,
        scrolls = held.scrolls,
        madeAtMs = held.madeAtMs,
        marks = held.marks,
    )
}

private fun bypass(old: Marks, new: Marks): Boolean =
    old.order != new.order || old.rows != new.rows || old.widgetRows != new.widgetRows ||
        (new.needsNewestMs ?: Long.MIN_VALUE) > (old.needsNewestMs ?: Long.MIN_VALUE) ||
        new.needsHeld > old.needsHeld ||
        !old.broken.containsAll(new.broken) ||
        (new.faultSinceMs ?: Long.MIN_VALUE) > (old.faultSinceMs ?: Long.MIN_VALUE) ||
        old.leave != new.leave || old.nextSoon != new.nextSoon ||
        old.live != new.live || old.agenda != new.agenda

private fun build(nowMs: Long, s: BentoInputs, order: List<Slot>, rows: Int): Arrangement {
    val leave = Bento.leaveNow(s.events, nowMs, s.leadMin)
    val today = Agenda.all(s.events.filter { it.id != leave?.event?.id }, nowMs)
    val next = today.firstOrNull()
    val rest = today.drop(1)
    val cards = order.filterIsInstance<Slot.Card>().map { it.id }.toSet()
    val soon = next != null && !next.current && next.event.beginMs - nowMs <= Bento.SOON_MS
    val fresh = { at: Long? -> at != null && nowMs - at < Bento.FRESH_MS }

    // A tile the user hid cannot lead, so the next case gets its turn.
    val lead: Tile? = when {
        leave != null -> Tile.LeaveNow
        soon && CardId.Today in cards -> Tile.NextEvent
        CardId.Homelab in cards && s.homelab.lit && fresh(s.faultSinceMs) -> Tile.Homelab
        CardId.NeedsYou in cards && s.needs.open > 0 && (s.needs.held > 0 || fresh(s.needsNewestMs)) -> Tile.NeedsYou
        else -> null
    }
    val musicShown = s.musicPlaying || (s.musicPausedAtMs?.let { nowMs - it <= Bento.MUSIC_PAUSED_MS } == true)

    // Level n applies the first n steps of the fit collapse order.
    fun sized(level: Int): List<Sized> = buildList {
        if (leave != null) add(Sized(Tile.LeaveNow, 4, 2, true))
        for (slot in order) when (slot) {
            is Slot.Widget -> add(Sized(Tile.Widget(slot.id), 4, Bento.widgetGridRows(s.widgetRows[slot.id] ?: 1)))
            is Slot.Card -> when (slot.id) {
                CardId.NeedsYou -> when {
                    lead == Tile.NeedsYou -> add(Sized(Tile.NeedsYou, 2, 2, true))
                    s.needs.open > 0 -> add(Sized(Tile.NeedsYou, 2, 1))
                    level < 4 -> add(Sized(Tile.NeedsYou, 1, 1))
                }
                CardId.Today -> {
                    if (next != null) {
                        val big = lead == Tile.NextEvent
                        add(Sized(Tile.NextEvent, 2, if (big) 2 else 1, big))
                    }
                    if (rest.isNotEmpty()) add(Sized(Tile.RestOfToday, 2, if (level >= 1) 1 else minOf(rest.size, Bento.MAX_REST_ROWS)))
                }
                CardId.Glance -> if (s.notes > 0 && level < 2) add(Sized(Tile.Notifications, 4, 1))
                CardId.Music -> if (musicShown) add(Sized(Tile.Music, if (level >= 3) 2 else 4, 1))
                CardId.Homelab -> when {
                    lead == Tile.Homelab -> add(Sized(Tile.Homelab, 2, 2, true))
                    s.homelab.lit -> add(Sized(Tile.Homelab, 2, 1))
                    level < 4 -> add(Sized(Tile.Homelab, 1, 1))
                }
                CardId.Alarm -> Unit
            }
        }
    }

    val avail = (rows - Bento.FIRST_ROW).coerceAtLeast(0)
    fun used(p: List<Placed>) = (p.maxOfOrNull { it.row + it.h } ?: Bento.FIRST_ROW) - Bento.FIRST_ROW
    var level = 0
    var placed = pack(sized(0))
    while (level < 4 && used(placed) > avail) placed = pack(sized(++level))

    val marks = Marks(
        order = order,
        rows = rows,
        widgetRows = s.widgetRows,
        needsNewestMs = s.needsNewestMs,
        needsHeld = s.needs.held,
        broken = s.homelab.broken.map { it.name }.toSet(),
        faultSinceMs = s.faultSinceMs,
        leave = leave?.let { it.event.id to it.late },
        nextSoon = soon,
        live = today.filter { it.current }.map { it.event.id }.toSet(),
        agenda = today.map { it.event.id }.toSet(),
    )
    return Arrangement(
        placed = placed,
        lead = lead,
        chip = s.notes > 0 && CardId.Glance in cards && level >= 2,
        scrolls = used(placed) > avail,
        leave = leave,
        next = next,
        rest = rest,
        madeAtMs = nowMs,
        marks = marks,
    )
}
