package io.github.egoushka.tin.home

import io.github.egoushka.tin.home.search.Fold
import io.github.egoushka.tin.home.search.Index
import org.junit.Assert.assertEquals
import org.junit.Test

/** H6: app search ranking. */
class AppSearchTest {
    private val apps = listOf("Signal", "Google Maps", "Messages", "Gmail", "Café Kyiv", "Камера", "Settings")
    private val index = Index(apps) { it }

    private fun search(q: String) = index.match(q).map { it.item }

    @Test
    fun prefixBeatsWordStartBeatsSubstring() {
        assertEquals(listOf("Messages", "Google Maps", "Gmail"), search("m"))
        assertEquals(listOf("Gmail", "Google Maps", "Messages", "Settings", "Signal"), search("g"))
        assertEquals("Google Maps", search("maps").single())
    }

    @Test
    fun ignoresCaseAndDiacritics() {
        assertEquals(listOf("Café Kyiv"), search("CAFE"))
        assertEquals(listOf("Камера"), search("кам"))
    }

    @Test
    fun emptyQueryKeepsEverythingAToZ() {
        assertEquals(apps.sortedBy { Fold.of(it) }, search("  "))
    }

    @Test
    fun noMatchIsEmpty() {
        assertEquals(emptyList<String>(), search("zzz"))
    }
}
