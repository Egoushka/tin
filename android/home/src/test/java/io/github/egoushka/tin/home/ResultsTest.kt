package io.github.egoushka.tin.home

import android.content.ComponentName
import io.github.egoushka.tin.home.apps.AppEntry
import io.github.egoushka.tin.home.search.Access
import io.github.egoushka.tin.home.search.Contact
import io.github.egoushka.tin.home.search.Hit
import io.github.egoushka.tin.home.search.Index
import io.github.egoushka.tin.home.search.Results
import io.github.egoushka.tin.home.search.Section
import io.github.egoushka.tin.home.search.ShortcutEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** HS3, HS4, HS5, HS6: how the groups rank against each other, which row Enter opens, and the permission rows. */
class ResultsTest {
    private fun apps(vararg labels: String) = Index(labels.map { AppEntry(it, ComponentName("p", "c"), null) }) { it.label }
    private fun shortcuts(vararg labels: String) = Index(labels.map { ShortcutEntry(it, "p", it, null) }) { it.label }
    private fun people(vararg names: String) = Index(names.mapIndexed { i, n -> Contact(i + 1L, "k$i", n, true) }) { it.name }

    private val noApps = apps()
    private val noShortcuts = shortcuts()
    private val noPeople = people()

    // Labels, not entries: an app's key needs a real ComponentName, which unit tests do not have.
    private fun label(hit: Hit): String = when (hit) {
        is Hit.App -> hit.app.label
        is Hit.Shortcut -> hit.shortcut.label
        is Hit.Person -> hit.contact.name
        Hit.Allow -> "<allow>"
        Hit.Off -> "<off>"
        is Hit.Ask -> "<ask ${hit.query}>"
        is Hit.Web -> "<web ${hit.query}>"
    }

    private fun Results.shape() = groups.map { it.section to it.hits.map(::label) }

    private fun of(q: String, a: Index<AppEntry> = noApps, s: Index<ShortcutEntry> = noShortcuts, p: Index<Contact> = noPeople, access: Access = Access.Granted) =
        Results.of(q, a, s, p, access)

    @Test
    fun blankQueryHasNoResults() {
        val r = of("   ", apps("Maps"), p = people("Marta"))
        assertTrue(r.groups.isEmpty())
        assertNull(r.top)
    }

    @Test
    fun askAndWebAreAlwaysTheLastGroup() {
        val r = of("zzz", apps("Maps"), p = people("Marta"))
        assertEquals(listOf(Section.AskOrSearch to listOf("<ask zzz>", "<web zzz>")), r.shape())
    }

    @Test
    fun appsComeFirstWhenTheyTieWithPeople() {
        val r = of("ma", apps("Maps"), p = people("Marta"))
        assertEquals(
            listOf(
                Section.Apps to listOf("Maps"),
                Section.People to listOf("Marta"),
                Section.AskOrSearch to listOf("<ask ma>", "<web ma>"),
            ),
            r.shape(),
        )
        assertEquals("Maps", r.top?.let(::label))
    }

    @Test
    fun aPersonWhoMatchesBetterComesBeforeTheApps() {
        val r = of("mar", apps("Smart Home"), p = people("Marta"))
        assertEquals(listOf(Section.People, Section.Apps, Section.AskOrSearch), r.groups.map { it.section })
        assertEquals("Marta", r.top?.let(::label))
    }

    @Test
    fun peopleLeadWhenNoAppMatches() {
        val r = of("ір", apps("Maps"), p = people("Ірина"))
        assertEquals(listOf(Section.People, Section.AskOrSearch), r.groups.map { it.section })
        assertEquals("Ірина", r.top?.let(::label))
    }

    @Test
    fun aShortcutFollowsAnAppOfTheSameTier() {
        val r = of("c", apps("Camera", "Chrome", "Music"), shortcuts("Compose", "Capture"))
        assertEquals(listOf("Camera", "Chrome", "Capture", "Compose", "Music"), r.groups.first().hits.map(::label))
    }

    @Test
    fun appsAndShortcutsAreCappedTogether() {
        val many = Array(10) { "App${it + 10}" }
        val r = of("app", apps(*many), shortcuts("App shortcut"))
        assertEquals(Results.APPS_CAP, r.groups.first().hits.size)
        assertEquals(Section.Apps, r.groups.first().section)
    }

    @Test
    fun peopleAreCapped() {
        val r = of("a", p = people("Anna", "Alex", "Alina", "Andriy", "Arsen", "Artem"))
        val group = r.groups.first { it.section == Section.People }
        assertEquals(Results.PEOPLE_CAP, group.hits.size)
        assertEquals(listOf("Alex", "Alina", "Andriy", "Anna"), group.hits.map(::label))
    }

    @Test
    fun enterOpensTheFirstRowThatOpens() {
        assertEquals("Maps", of("map", apps("Maps")).top?.let(::label))
        assertEquals("Capture", of("cap", s = shortcuts("Capture")).top?.let(::label))
        assertEquals("<ask zzz>", of("zzz").top?.let(::label))
    }

    @Test
    fun theWebRowDoesNotOpenOnEnterButAskDoes() {
        assertTrue(Hit.Ask("x").opens)
        assertFalse(Hit.Web("x").opens)
        assertFalse(Hit.Allow.opens)
        assertFalse(Hit.Off.opens)
    }

    @Test
    fun theQueryOnTheWebAndAskRowsIsTrimmed() {
        val last = of("  zzz  ").groups.last().hits
        assertEquals(Hit.Ask("zzz"), last[0])
        assertEquals(Hit.Web("zzz"), last[1])
    }

    @Test
    fun beforeTheGrantOneAllowRowStandsInForPeople() {
        val r = of("ma", p = people("Marta"), access = Access.Ask)
        assertEquals(
            listOf(Section.People to listOf("<allow>"), Section.AskOrSearch to listOf("<ask ma>", "<web ma>")),
            r.shape(),
        )
    }

    @Test
    fun anAllowRowIsNeverTheTopRow() {
        val r = of("ma", apps("Maps"), p = people("Marta"), access = Access.Ask)
        assertEquals("Maps", r.top?.let(::label))
        assertEquals("<ask zzz>", of("zzz", access = Access.Ask).top?.let(::label))
    }

    @Test
    fun afterARefusalOneOffRowSaysSo() {
        val r = of("ma", p = people("Marta"), access = Access.Denied)
        assertEquals(listOf(Section.People to listOf("<off>")), r.shape().take(1))
    }

    @Test
    fun grantedWithNoMatchHasNoPeopleGroup() {
        val r = of("zzz", apps("Maps"), p = people("Marta"))
        assertFalse(r.groups.any { it.section == Section.People })
    }

    @Test
    fun noPermissionRowAppearsOnceAccessIsGranted() {
        val r = of("ma", apps("Maps"), p = people("Marta"))
        assertFalse(r.groups.flatMap { it.hits }.any { it === Hit.Allow || it === Hit.Off })
    }
}
