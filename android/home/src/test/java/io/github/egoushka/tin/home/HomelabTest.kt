package io.github.egoushka.tin.home

import io.github.egoushka.tin.home.signals.Homelab
import io.github.egoushka.tin.home.signals.Probe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HomelabTest {
    // Results are oldest first, as Gatus returns them.
    private fun ep(name: String, vararg ok: Boolean) = Probe(name, "core", ok.toList())

    @Test fun oneFailedCheckStaysCalm() {
        val lab = Homelab.of(listOf(ep("a", true, true, false), ep("b", true)))
        assertFalse(lab.lit)
        assertEquals(2, lab.total)
    }

    @Test fun twoFailedChecksInARowBreak() {
        val lab = Homelab.of(listOf(ep("b", true, false, false), ep("a", false, false), ep("c", true, true)))
        assertEquals(listOf("a", "b"), lab.broken.map { it.name })
    }

    @Test fun failuresMustBeConsecutiveAndNewest() {
        assertFalse(Homelab.of(listOf(ep("a", false, false, true))).lit)
        assertFalse(Homelab.of(listOf(ep("a", false, true, false))).lit)
    }

    @Test fun noResultsYetIsNotBroken() {
        assertFalse(Homelab.of(listOf(ep("a"))).lit)
        assertEquals(0, Homelab.of(emptyList()).total)
    }
}
