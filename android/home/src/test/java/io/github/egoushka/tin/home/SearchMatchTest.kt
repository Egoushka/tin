package io.github.egoushka.tin.home

import io.github.egoushka.tin.home.search.Fold
import io.github.egoushka.tin.home.search.Index
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HS2: Ukrainian names and diacritics, and the tier of each kind of hit. */
class SearchMatchTest {
    private fun find(names: List<String>, q: String) = Index(names) { it }.match(q).map { it.item }

    @Test
    fun ukrainianIgnoresCase() {
        val names = listOf("Ірина", "Олег")
        assertEquals(listOf("Ірина"), find(names, "ірина"))
        assertEquals(listOf("Ірина"), find(names, "ІРИНА"))
        assertEquals(listOf("Ірина"), find(names, "іри"))
    }

    @Test
    fun shortIFoldsToI() {
        assertEquals(listOf("Йосип"), find(listOf("Йосип", "Олег"), "йосип"))
    }

    @Test
    fun everyApostropheIsIgnored() {
        val names = listOf("Мар'яна", "Мар’яна", "Марʼяна", "Марина")
        assertEquals(setOf("Мар'яна", "Мар’яна", "Марʼяна"), find(names, "марян").toSet())
        assertEquals(3, find(names, "мар'ян").size)
    }

    @Test
    fun latinAccentsFold() {
        assertEquals(listOf("José"), find(listOf("José", "Joan"), "jose"))
        assertEquals(listOf("Zoë"), find(listOf("Zoë", "Zack"), "zoe"))
    }

    @Test
    fun latinQueryDoesNotFindCyrillicNames() {
        assertEquals(emptyList<String>(), find(listOf("Ірина", "Олег"), "irina"))
    }

    @Test
    fun foldKeepsLettersAndDropsMarks() {
        assertEquals("zoe", Fold.of("Zoë"))
        assertEquals("маряна", Fold.of("Мар’яна"))
        assertEquals("иосип", Fold.of("Йосип"))
    }

    @Test
    fun tiersAreOrderedPrefixWordStartSubstring() {
        val index = Index(listOf("Mail", "Google Mail", "Gmail", "Dark-Mode", "Notes")) { it }
        val tiers = index.match("ma").associate { it.item to it.tier }
        assertEquals(mapOf("Mail" to 0, "Google Mail" to 1, "Gmail" to 2), tiers)
    }

    @Test
    fun wordStartsAfterHyphenUnderscoreDotAndColon() {
        val index = Index(listOf("dark-mode", "dark_mode", "dark.mode", "dark:mode", "darkmode")) { it }
        val hits = index.match("mod")
        assertEquals(listOf(1, 1, 1, 1, 2), hits.map { it.tier })
        assertEquals("darkmode", hits.last().item)
    }

    @Test
    fun equalTiersStayAToZ() {
        val hits = Index(listOf("Beta", "Alpha", "Bravo", "Albatross")) { it }.match("b")
        assertEquals(listOf("Beta", "Bravo", "Albatross"), hits.map { it.item })
        assertEquals(listOf("Albatross", "Alpha"), Index(listOf("Alpha", "Albatross")) { it }.match("al").map { it.item })
    }

    @Test
    fun blankQueryListsEverythingAtTheBlankTier() {
        val hits = Index(listOf("b", "a")) { it }.match("   ")
        assertEquals(listOf("a", "b"), hits.map { it.item })
        assertTrue(hits.all { it.tier == Index.BLANK })
    }

    @Test
    fun surroundingSpacesInTheQueryAreIgnored() {
        assertEquals(listOf("Signal"), find(listOf("Signal", "Settings"), "  sig "))
    }
}
