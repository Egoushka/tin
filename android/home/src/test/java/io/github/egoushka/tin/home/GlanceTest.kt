package io.github.egoushka.tin.home

import io.github.egoushka.tin.home.signals.Glance
import io.github.egoushka.tin.home.signals.Note
import io.github.egoushka.tin.home.signals.NoteKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** A6: the dots read the count of every package, not only the three chips. */
class GlanceTest {
    private fun note(pkg: String, at: Long) = Note(pkg, pkg.uppercase(), "t", "x", at, NoteKind.Other)

    @Test
    fun everyPackageIsCountedAndTheChipsStayAtThree() {
        val g = Glance.of((1..6).map { note("p$it", it.toLong()) } + note("p1", 9))
        assertEquals(Glance.MAX_CHIPS, g.apps.size)
        assertEquals(6, g.byPackage.size)
        assertEquals(2, g.byPackage["p1"])
        assertEquals(1, g.byPackage["p6"])
        assertEquals(7, g.total)
    }

    @Test
    fun noNotificationsMeansNoDots() {
        assertEquals(emptyMap<String, Int>(), Glance.of(emptyList()).byPackage)
    }
}
