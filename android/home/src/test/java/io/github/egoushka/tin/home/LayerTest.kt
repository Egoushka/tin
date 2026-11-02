package io.github.egoushka.tin.home

import io.github.egoushka.tin.home.layer.Age
import io.github.egoushka.tin.home.layer.AgeUnit
import io.github.egoushka.tin.home.layer.Freshness
import io.github.egoushka.tin.home.signals.Buds
import io.github.egoushka.tin.home.signals.BudsReader
import io.github.egoushka.tin.home.signals.Glance
import io.github.egoushka.tin.home.signals.Note
import io.github.egoushka.tin.home.signals.NoteKind
import io.github.egoushka.tin.home.theme.Lucide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slice 2: the glance model, source age, stale rule and earbud readings. */
class LayerTest {
    private fun note(pkg: String, at: Long, kind: NoteKind = NoteKind.Message) = Note(pkg, pkg.uppercase(), "t", "x", at, kind)

    @Test fun glanceCountsPerAppMostFirstAndKeepsLatest() {
        val g = Glance.of(listOf(note("a", 1), note("b", 5), note("b", 3), note("c", 9)))
        assertEquals(listOf("b", "c", "a"), g.apps.map { it.pkg }) // b has 2; c is newer than a
        assertEquals(4, g.total)
        assertEquals(9L, g.latest?.whenMs)
    }

    @Test fun glanceShowsAtMostThreeChips() {
        val g = Glance.of((1..6).map { note("p$it", it.toLong()) })
        assertEquals(Glance.MAX_CHIPS, g.apps.size)
        assertEquals(6, g.total)
    }

    @Test fun emptyGlanceHasNoLatest() {
        assertNull(Glance.of(emptyList()).latest)
        assertEquals(0, Glance.Empty.total)
    }

    @Test fun ageUnits() {
        val min = 60_000L
        assertEquals(Age(AgeUnit.Now, 0), Freshness.age(100 * min, 100 * min - 20_000))
        assertEquals(Age(AgeUnit.Min, 5), Freshness.age(100 * min, 95 * min))
        assertEquals(Age(AgeUnit.Hour, 2), Freshness.age(300 * min, 180 * min))
        assertEquals(Age(AgeUnit.Day, 1), Freshness.age(48 * 60 * min, 24 * 60 * min))
    }

    @Test fun staleAfterThreeMissedRefreshesOnly() {
        assertFalse(Freshness.isStale(300_000, 0, 100_000)) // exactly three intervals
        assertTrue(Freshness.isStale(300_001, 0, 100_000))
        assertFalse(Freshness.isStale(Long.MAX_VALUE / 2, 0, null)) // push sources never age out
    }

    @Test fun budReadingsKeepOnlyRealPercentages() {
        assertNull(BudsReader.percent(-1))
        assertNull(BudsReader.percent(0))
        assertNull(BudsReader.percent(null))
        assertEquals(76, BudsReader.percent(76))
        assertNull(BudsReader.percent(101))
    }

    @Test fun budLabel() {
        assertEquals("80 · 76%", Buds(80, 76, null).label())
        assertEquals("76%", Buds(null, 76, null).label())
        assertEquals("55%", Buds(null, null, 55).label())
        assertEquals("", Buds(null, null, null).label())
    }

    @Test fun polygonPointsArePairsNotTokens() {
        assertEquals(listOf("M5 4L15 12L5 20L5 4Z"), Lucide.toPathData("""<polygon points="5 4 15 12 5 20 5 4"/>"""))
    }
}
