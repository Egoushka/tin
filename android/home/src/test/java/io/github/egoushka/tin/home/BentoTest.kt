package io.github.egoushka.tin.home

import io.github.egoushka.tin.home.layout.Arrangement
import io.github.egoushka.tin.home.layout.Bento
import io.github.egoushka.tin.home.layout.BentoInputs
import io.github.egoushka.tin.home.layout.CardId
import io.github.egoushka.tin.home.layout.Placed
import io.github.egoushka.tin.home.layout.Sized
import io.github.egoushka.tin.home.layout.Slot
import io.github.egoushka.tin.home.layout.Tile
import io.github.egoushka.tin.home.layout.arrange
import io.github.egoushka.tin.home.layout.pack
import io.github.egoushka.tin.home.signals.Event
import io.github.egoushka.tin.home.signals.Homelab
import io.github.egoushka.tin.home.signals.NeedsItem
import io.github.egoushka.tin.home.signals.NeedsYou
import io.github.egoushka.tin.home.signals.Probe
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The living bento's sizing rules (spec 2026-11-15-home-living-bento, LB1 to LB4). */
class BentoTest {
    private val zone = ZoneId.of("Europe/Kyiv")
    private val day = LocalDate.of(2026, 11, 15)
    private fun at(h: Int, m: Int = 0) = day.atTime(LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()
    private val min = 60_000L

    private fun event(id: Long, title: String, from: Long, to: Long, where: String? = null) = Event(id, title, from, to, where)
    private val standup = event(1, "Standup", at(10, 30), at(10, 45))
    private val review = event(2, "Sprint review", at(12, 15), at(12, 50), "Zoom")
    private val lunch = event(3, "Lunch", at(13), at(14), "Podil")

    private fun needs(open: Int, held: Int = 0) =
        NeedsYou.of(List(open.coerceAtMost(3)) { NeedsItem("$it", "PR $it", "gh", at(9) + it, false, "approvals://item/$it") }, open, held)

    private val broken = Homelab.of(listOf(Probe("nickel", "core", listOf(false, false))))

    private val order = listOf(Slot.Card(CardId.NeedsYou), Slot.Card(CardId.Today), Slot.Card(CardId.Homelab), Slot.Card(CardId.Music))

    /** The board fixture: 4 open (newest at 10:11), a fault since 09:00, music playing. */
    private fun board(events: List<Event>) = BentoInputs(
        events = events,
        needs = needs(4),
        needsNewestMs = at(10, 11),
        homelab = broken,
        faultSinceMs = at(9),
        musicPlaying = true,
    )

    private fun Arrangement.tile(t: Tile): Placed = placed.first { it.tile == t }

    // LB1
    @Test fun at1012NeedsYouLeadsAndTheRestStayCalm() {
        val a = arrange(at(10, 12), board(listOf(standup, lunch)), order, 8)
        assertEquals(Tile.NeedsYou, a.lead)
        a.tile(Tile.NeedsYou).let { assertTrue(it.lead); assertEquals(2 to 2, it.w to it.h) }
        a.tile(Tile.NextEvent).let { assertFalse(it.lead); assertEquals(2 to 1, it.baseW to it.baseH) }
        assertEquals(18L, (a.next!!.event.beginMs - at(10, 12)) / min)
        a.tile(Tile.Homelab).let { assertFalse(it.lead); assertEquals(2 to 1, it.baseW to it.baseH) }
        a.tile(Tile.Music).let { assertEquals(4 to 1, it.w to it.h) }
        assertNull(a.leave)
    }

    // LB2
    @Test fun leaveNowLeadsFrom1145AndGoesLateThenGone() {
        val s = board(listOf(standup, review, lunch))
        val a = arrange(at(12, 5), s, order, 8)
        assertEquals(Tile.LeaveNow, a.lead)
        assertEquals(25, a.leave!!.figureMin)
        assertFalse(a.leave!!.late)
        a.placed.first().let { assertEquals(Tile.LeaveNow, it.tile); assertEquals(Bento.FIRST_ROW, it.row); assertEquals(4 to 2, it.w to it.h) }
        a.tile(Tile.NeedsYou).let { assertEquals(2 to 1, it.baseW to it.baseH); assertFalse(it.lead) }
        assertEquals("Sprint review", a.next!!.event.title)

        val late = arrange(at(12, 31), s, order, 8)
        assertTrue(late.leave!!.late)
        assertEquals(29, late.leave!!.figureMin)
        assertEquals(at(13), late.leave!!.event.beginMs)

        assertNull(arrange(at(13), s, order, 8).leave)
        assertEquals(Tile.LeaveNow, arrange(at(11, 45), s, order, 8).lead)
        assertNull(arrange(at(11, 44), s, order, 8).leave)
    }

    @Test fun leadTimeMovesTheLeaveTime() {
        val s = board(listOf(lunch)).copy(leadMin = 60)
        assertEquals(at(12), arrange(at(11, 30), s, order, 8).leave!!.leaveAtMs)
        assertNull(arrange(at(10, 14), s, order, 8).leave)
    }

    @Test fun onlinePlacesNeverLeave() {
        val online = listOf(
            "Zoom", "https://meet.google.com/abc", "Google Meet", "Microsoft Teams", "webex room", "Online", "Conference call", "www.example.com",
        )
        online.forEachIndexed { i, where ->
            val e = event(10L + i, "x", at(13), at(14), where)
            assertNull(where, arrange(at(12, 30), board(listOf(e)), order, 8).leave)
        }
        assertNull(arrange(at(12, 30), board(listOf(event(9, "x", at(13), at(14), null))), order, 8).leave)
        assertNull(arrange(at(12, 30), board(listOf(event(9, "x", at(13), at(14), "  "))), order, 8).leave)
        assertNotNull(arrange(at(12, 30), board(listOf(lunch)), order, 8).leave)
    }

    @Test fun noEventsTodayDropsNextAndRest() {
        val a = arrange(at(10, 12), board(emptyList()), order, 8)
        assertNull(a.next)
        assertTrue(a.placed.none { it.tile == Tile.NextEvent || it.tile == Tile.RestOfToday })
    }

    @Test fun eventInProgressIsNextEventAndNeverLeads() {
        val running = event(5, "Planning", at(10), at(11))
        val a = arrange(at(10, 12), board(listOf(running)), order, 8)
        assertTrue(a.next!!.current)
        assertEquals(Tile.NeedsYou, a.lead)
        assertEquals(2 to 1, a.tile(Tile.NextEvent).let { it.baseW to it.baseH })
    }

    @Test fun nextEventWithin15MinutesLeadsBeforeNeedsYou() {
        val a = arrange(at(10, 15), board(listOf(standup)), order, 8)
        assertEquals(Tile.NextEvent, a.lead)
        assertEquals(2 to 2, a.tile(Tile.NextEvent).let { it.baseW to it.baseH })
        assertEquals(Tile.NeedsYou, arrange(at(10, 14), board(listOf(standup)), order, 8).lead)
    }

    @Test fun aFreshFaultLeadsButAnOldOneDoesNot() {
        val s = BentoInputs(homelab = broken, faultSinceMs = at(9, 50), needs = needs(2), needsNewestMs = at(9))
        assertEquals(Tile.Homelab, arrange(at(10), s, order, 8).lead)
        assertNull(arrange(at(10, 21), s, order, 8).lead)
    }

    @Test fun needsYouLeadsOnAHeldCallOrANewItemButNotOnOpenAlone() {
        val old = BentoInputs(needs = needs(3), needsNewestMs = at(8))
        assertNull(arrange(at(10), old, order, 8).lead)
        assertEquals(Tile.NeedsYou, arrange(at(10), old.copy(needs = needs(3, held = 1)), order, 8).lead)
        assertEquals(Tile.NeedsYou, arrange(at(10), old.copy(needsNewestMs = at(9, 45)), order, 8).lead)
    }

    @Test fun aHiddenTileCannotLeadSoTheNextCaseDoes() {
        val s = BentoInputs(homelab = broken, faultSinceMs = at(9, 50), needs = needs(1, held = 1))
        val noHomelab = order.filter { it != Slot.Card(CardId.Homelab) }
        assertEquals(Tile.NeedsYou, arrange(at(10), s, noHomelab, 8).lead)
    }

    @Test fun needsYouAtZeroIsOneByOne() {
        val a = arrange(at(10), BentoInputs(), order, 8)
        assertEquals(1 to 1, a.tile(Tile.NeedsYou).let { it.baseW to it.baseH })
        assertEquals(1 to 1, a.tile(Tile.Homelab).let { it.baseW to it.baseH })
        assertNull(a.lead)
    }

    @Test fun musicShowsWhilePlayingOrPausedWithinTenMinutes() {
        val o = listOf(Slot.Card(CardId.Music))
        assertTrue(arrange(at(10), BentoInputs(musicPlaying = true), o, 8).placed.isNotEmpty())
        assertTrue(arrange(at(10), BentoInputs(musicPausedAtMs = at(9, 50)), o, 8).placed.isNotEmpty())
        assertTrue(arrange(at(10), BentoInputs(musicPausedAtMs = at(9, 49)), o, 8).placed.isEmpty())
    }

    @Test fun widgetsAreFourWideAndScaleToTheGrid() {
        assertEquals(1, Bento.widgetGridRows(1))
        assertEquals(2, Bento.widgetGridRows(2))
        assertEquals(4, Bento.widgetGridRows(8))
        val a = arrange(at(10), BentoInputs(widgetRows = mapOf(7 to 2)), listOf(Slot.Widget(7)), 8)
        assertEquals(4 to 2, a.placed.single().let { it.w to it.h })
    }

    @Test fun fitCollapsesInTheListedOrder() {
        val many = (1L..6L).map { event(it, "e$it", at(15 + it.toInt()), at(16 + it.toInt())) }
        val s = board(many).copy(notes = 3, needs = needs(0), needsNewestMs = null, homelab = Homelab.Empty, faultSinceMs = null)
        val o = listOf(Slot.Card(CardId.Glance), Slot.Card(CardId.Today), Slot.Card(CardId.Music), Slot.Card(CardId.NeedsYou), Slot.Card(CardId.Homelab))
        val roomy = arrange(at(10), s, o, 20)
        assertEquals(3, roomy.tile(Tile.RestOfToday).baseH)
        assertFalse(roomy.chip)
        // Dropping the rest rows from 3 to 1 is the first thing to give.
        val tight = arrange(at(10), s, o, 6)
        assertEquals(1, tight.tile(Tile.RestOfToday).baseH)
        assertTrue(tight.placed.any { it.tile == Tile.Notifications })
        // Squeezed further: the chip, then Music at 2 wide, then the healthy tiles go; whatever remains may scroll.
        val tiny = arrange(at(10), s, o, 2)
        assertTrue(tiny.chip)
        assertEquals(2, tiny.tile(Tile.Music).baseW)
        assertTrue(tiny.placed.none { it.tile == Tile.Homelab || it.tile == Tile.NeedsYou })
    }

    @Test fun scrollsOnlyWhenEvenTheCollapsedPageIsTooTall() {
        val ws = (1..4).map { Slot.Widget(it) }
        val s = BentoInputs(widgetRows = (1..4).associateWith { 4 })
        assertTrue(arrange(at(10), s, ws, 5).scrolls)
        assertFalse(arrange(at(10), s, ws, 40).scrolls)
    }

    // LB3
    private val shapes = listOf(1 to 1, 2 to 1, 2 to 2, 4 to 1, 4 to 2, 2 to 3)

    private fun sequences(len: Int): List<List<Pair<Int, Int>>> =
        if (len == 0) listOf(emptyList()) else sequences(len - 1).flatMap { s -> shapes.map { s + it } }

    @Test fun packingNeverOverlapsKeepsOrderAndLeavesNoGapBeyondAStretch() {
        for (len in 0..5) for (seq in sequences(len)) {
            val items = seq.mapIndexed { i, (w, h) -> Sized(Tile.Widget(i), w, h) }
            val placed = pack(items, firstRow = 0)
            val label = seq.toString()
            assertEquals(label, items.map { it.tile }, placed.map { it.tile })

            val cells = HashSet<Pair<Int, Int>>()
            for (p in placed) {
                assertTrue(label, p.col >= 0 && p.col + p.w <= Bento.COLUMNS)
                assertTrue(label, p.w >= p.baseW && p.h >= p.baseH)
                for (x in p.col until p.col + p.w) for (y in p.row until p.row + p.h) assertTrue(label, cells.add(x to y))
            }
            val origins = placed.map { it.row * Bento.COLUMNS + it.col }
            assertEquals(label, origins.sorted(), origins)

            val rows = (placed.maxOfOrNull { it.row + it.h } ?: 0)
            for (y in 0 until rows) for (x in 0 until Bento.COLUMNS) assertTrue("$label gap at $x,$y", (x to y) in cells)
        }
    }

    // LB4
    @Test fun aFlippingSignalChangesTheLayoutOnceInFiveMinutes() {
        val base = board(listOf(standup, lunch)).copy(needsNewestMs = at(9, 59))
        val o = order + Slot.Card(CardId.Glance)
        val first = arrange(at(10), base, o, 8)
        var cur = first
        var changes = 0
        for (m in 1..4) {
            val flipped = base.copy(notes = if (m % 2 == 1) 3 else 0)
            val next = arrange(at(10) + m * min, flipped, o, 8, cur)
            if (next.placed != cur.placed) changes++
            cur = next
        }
        assertEquals(0, changes)
        assertEquals(first.madeAtMs, cur.madeAtMs)
        val after = arrange(at(10, 5), base.copy(notes = 3), o, 8, cur)
        assertEquals(at(10, 5), after.madeAtMs)
        assertTrue(after.placed.any { it.tile == Tile.Notifications })
    }

    private fun rebuilt(held: Arrangement, now: Long, s: BentoInputs, o: List<Slot> = order) =
        arrange(now, s, o, 8, held).madeAtMs == now

    @Test fun newInformationBypassesTheHold() {
        val calm = BentoInputs(needs = needs(2), needsNewestMs = at(8), events = listOf(standup, lunch))
        val held = arrange(at(10), calm, order, 8)
        assertTrue(rebuilt(held, at(10, 1), calm.copy(musicPlaying = true)))
        val playing = arrange(at(10), calm.copy(musicPlaying = true), order, 8)
        assertFalse(rebuilt(playing, at(10, 1), calm.copy(musicPausedAtMs = at(10, 1))))

        val item = calm.copy(needs = needs(3), needsNewestMs = at(10, 1))
        assertTrue(rebuilt(held, at(10, 1), item))
        assertEquals(Tile.NeedsYou, arrange(at(10, 1), item, order, 8, held).lead)

        assertTrue(rebuilt(held, at(10, 1), calm.copy(needs = needs(2, held = 1))))
        assertTrue(rebuilt(held, at(10, 1), calm.copy(homelab = broken, faultSinceMs = at(10, 1))))
        // A fault clearing is not new information.
        val faulty = arrange(at(10), calm.copy(homelab = broken, faultSinceMs = at(10)), order, 8)
        assertFalse(rebuilt(faulty, at(10, 1), calm))
    }

    @Test fun boundariesBypassTheHold() {
        val s = BentoInputs(events = listOf(standup, lunch))
        assertTrue(rebuilt(arrange(at(10, 14), s, order, 8), at(10, 15), s))
        assertTrue(rebuilt(arrange(at(10, 29), s, order, 8), at(10, 30), s))
        assertTrue(rebuilt(arrange(at(10, 40), s, order, 8), at(10, 45), s))
        assertTrue(rebuilt(arrange(at(11, 44), s, order, 8), at(11, 45), s))
        assertTrue(rebuilt(arrange(at(12, 29), s, order, 8), at(12, 31), s))
        assertTrue(rebuilt(arrange(at(12, 59), s, order, 8), at(13), s))
    }

    @Test fun editingTheOrderOrRowsRebuildsAtOnce() {
        val s = BentoInputs(events = listOf(standup))
        val held = arrange(at(10), s, order, 8)
        assertTrue(rebuilt(held, at(10, 1), s, order.reversed()))
        assertEquals(at(10, 1), arrange(at(10, 1), s, order, 6, held).madeAtMs)
    }
}
