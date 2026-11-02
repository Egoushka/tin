package io.github.egoushka.tin.home

import android.content.ComponentName
import io.github.egoushka.tin.home.apps.AppEntry
import io.github.egoushka.tin.home.search.Access
import io.github.egoushka.tin.home.search.Contact
import io.github.egoushka.tin.home.search.Index
import io.github.egoushka.tin.home.search.Results
import io.github.egoushka.tin.home.search.ShortcutEntry
import org.junit.Assert.assertTrue
import org.junit.Test

/** HS9: one keystroke over 300 apps, 50 shortcuts and 1000 contacts stays instant. */
class SearchSpeedTest {
    private val first = listOf("Anna", "Олег", "José", "Ірина", "Марія", "Zoë", "Andriy", "Світлана", "Max", "Юлія", "Peter", "Катерина")
    private val last = listOf("Kovalenko", "Шевченко", "García", "Мельник", "O'Brien", "Мар'яненко", "Smith", "Бондаренко", "Müller", "Ткачук")
    private val words = listOf("Mail", "Maps", "Камера", "Notes", "Music", "Погода", "Photos", "Calendar", "Files", "Clock", "Chat", "Banking")

    private val apps = Index(List(300) { AppEntry("${words[it % words.size]} ${it / words.size + 1}", ComponentName("p", "c$it"), null) }) { it.label }
    private val shortcuts = Index(List(50) { ShortcutEntry("s$it", "p$it", "${words[(it * 5) % words.size]} shortcut $it", null) }) { it.label }
    private val people = Index(List(1000) { Contact(it + 1L, "k$it", "${first[it % first.size]} ${last[(it / first.size) % last.size]} ${it}", it % 3 != 0) }) { it.name }

    private val queries = listOf("a", "ка", "mar", "іри", "jose", "ma", "zzz", "the")

    private fun medianMs(q: String): Double {
        repeat(WARMUP) { Results.of(q, apps, shortcuts, people, Access.Granted) }
        val runs = DoubleArray(RUNS) {
            val t0 = System.nanoTime()
            Results.of(q, apps, shortcuts, people, Access.Granted)
            (System.nanoTime() - t0) / 1e6
        }
        runs.sort()
        return runs[RUNS / 2]
    }

    @Test
    fun aKeystrokeFiltersInUnderTheBudget() {
        val medians = queries.associateWith(::medianMs)
        val worst = medians.maxOf { it.value }
        println("SEARCH-SPEED apps=${apps.size} shortcuts=${shortcuts.size} people=${people.size} runs=$RUNS " +
            medians.entries.joinToString(" ") { "'${it.key}'=${"%.3f".format(it.value)}ms" } + " worst=${"%.3f".format(worst)}ms")
        assertTrue("worst median ${worst} ms is over the $BUDGET_MS ms budget", worst < BUDGET_MS)
    }

    private companion object {
        const val WARMUP = 50
        const val RUNS = 200
        const val BUDGET_MS = 8.0
    }
}
