package io.github.egoushka.tin.home

import io.github.egoushka.tin.home.signals.Agenda
import io.github.egoushka.tin.home.signals.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slice 3: which events the Today card shows, which is current, which clash. */
class AgendaTest {
    private val h = 3_600_000L
    private fun ev(id: Long, from: Long, to: Long) = Event(id, "e$id", from * h, to * h, null)

    @Test fun endedEventsDropAndRestSortByStart() {
        val a = Agenda.of(listOf(ev(3, 14, 15), ev(1, 8, 9), ev(2, 10, 11)), 9 * h + 1)
        assertEquals(listOf(2L, 3L), a.items.map { it.event.id })
    }

    @Test fun currentIsTheOneRunningNow() {
        val a = Agenda.of(listOf(ev(1, 9, 11), ev(2, 12, 13)), 10 * h)
        assertEquals(listOf(true, false), a.items.map { it.current })
        assertTrue(a.hasCurrent)
    }

    @Test fun overlapMarksBothAndTouchingDoesNot() {
        val clash = Agenda.of(listOf(ev(1, 9, 11), ev(2, 10, 12)), 8 * h)
        assertTrue(clash.items.all { it.clash })
        val touching = Agenda.of(listOf(ev(1, 9, 10), ev(2, 10, 11)), 8 * h)
        assertFalse(touching.hasClash)
    }

    @Test fun clashWithAnEventBelowTheFoldStillMarks() {
        val events = listOf(ev(1, 9, 10), ev(2, 10, 11), ev(3, 11, 12), ev(4, 11, 13))
        val a = Agenda.of(events, 8 * h)
        assertEquals(Agenda.MAX_ROWS, a.items.size)
        assertEquals(1, a.more)
        assertTrue(a.items.last().clash) // e3 overlaps e4, which is the hidden one
    }

    @Test fun emptyDay() {
        assertTrue(Agenda.of(emptyList(), 0).items.isEmpty())
        assertFalse(Agenda.Empty.hasCurrent)
    }
}
