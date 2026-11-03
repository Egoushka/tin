package io.github.egoushka.tin.home

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.egoushka.tin.home.layout.CardId
import io.github.egoushka.tin.home.layout.HomeLayout
import io.github.egoushka.tin.home.layout.LayoutStore
import io.github.egoushka.tin.home.layout.PinStore
import io.github.egoushka.tin.home.layout.Slot
import io.github.egoushka.tin.home.pages.Board
import io.github.egoushka.tin.home.pages.PageItem
import io.github.egoushka.tin.home.pages.PageStore
import io.github.egoushka.tin.home.pages.Pages
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PageStoreTest {
    private val dir = File.createTempFile("tin-pages", "").apply { delete(); mkdirs() }
    private val file = File(dir, "tin.tune.preferences_pb")
    private val scopes = mutableListOf<CoroutineScope>()

    private class Stores(val pages: PageStore, val layout: LayoutStore, val pins: PinStore)

    /** A new DataStore over the same file stands in for the process starting again. */
    private fun open(): Stores {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
        val ds = PreferenceDataStoreFactory.create(scope = scope) { file }
        return Stores(PageStore(ds), LayoutStore(ds), PinStore(ds))
    }

    /** DataStore lets go of the file only once its scope has completed, so open again after that, not right after cancel. */
    private suspend fun stop() = scopes.removeAt(0).coroutineContext[Job]!!.cancelAndJoin()

    @After fun close() {
        scopes.forEach { it.cancel() }
        dir.deleteRecursively()
    }

    private fun app(n: Int) = PageItem.App("com.example.$n/Main#0")

    @Test fun `an empty file reads as no extra pages`() = runBlocking {
        assertEquals(Pages(), open().pages.pages.first())
    }

    @Test fun `a change is read by a new store over the same file`() = runBlocking {
        val first = open().pages
        first.update { it.addPage().addPage().add(1, app(1)).add(1, PageItem.Shortcut("p", "s", 0, "S")).add(0, PageItem.Widget(11, 2)) }
        val expected = first.pages.first()
        stop()
        assertEquals(expected, open().pages.pages.first())
        assertEquals(2, expected.count)
    }

    @Test fun `concurrent updates do not lose a change`() = runBlocking {
        val store = open().pages
        store.update { it.addPage() }
        (1..20).map { n -> async(Dispatchers.IO) { store.update { it.add(0, app(n)) } } }.awaitAll()
        assertEquals((1..20).map { app(it).id }.toSet(), store.pages.first().pages[0].map { it.id }.toSet())
    }

    @Test fun `pins, layout and pages share a file without touching each other`() = runBlocking {
        val s = open()
        s.layout.update { it.hide(Slot.Card(CardId.Music)).addWidget(7, 2) }
        s.pins.pin("a")
        s.pages.update { it.addPage().add(0, app(1)) }
        s.layout.update { it.hide(Slot.Card(CardId.Today)) }
        assertEquals(setOf<Slot>(Slot.Card(CardId.Music), Slot.Card(CardId.Today)), s.layout.layout.first().hidden)
        assertEquals(setOf(7), s.layout.layout.first().widgetIds)
        assertEquals(listOf("a"), s.pins.keys.first())
        assertEquals(listOf(app(1).id), s.pages.pages.first().pages[0].map { it.id })
    }

    @Test fun `a board update writes layout and pages together`() = runBlocking {
        val s = open()
        s.pages.updateBoard { Board(it.layout.addWidget(7, 3), it.pages.addPage()) }
        val board = s.pages.board.first()
        assertEquals(setOf(7), board.layout.widgetIds)
        assertEquals(1, board.pages.count)
        assertEquals(setOf(7), s.layout.layout.first().widgetIds)
    }

    @Test fun `moving a widget to a page leaves its id in exactly one of layout and pages, and back`() = runBlocking {
        val s = open()
        s.pages.updateBoard { Board(it.layout.addWidget(7, 3), it.pages.addPage()) }
        s.pages.updateBoard { it.moveWidgetToPage(7, 0) }
        val onPage = s.pages.board.first()
        assertEquals(emptySet<Int>(), onPage.layout.widgetIds)
        assertEquals(setOf(7), onPage.pages.widgetIds)
        assertEquals(PageItem.Widget(7, 3), onPage.pages.pages[0].single())
        assertEquals(null, onPage.layout.rows[7])

        s.pages.updateBoard { it.moveWidgetToMain(7) }
        val back = s.pages.board.first()
        assertEquals(setOf(7), back.layout.widgetIds)
        assertEquals(3, back.layout.rows[7])
        assertEquals(emptySet<Int>(), back.pages.widgetIds)
    }

    @Test fun `a widget move survives a restart in one piece`() = runBlocking {
        val first = open().pages
        first.updateBoard { Board(it.layout.addWidget(7, 2), it.pages.addPage()) }
        first.updateBoard { it.moveWidgetToPage(7, 0) }
        stop()
        val board = open().pages.board.first()
        assertEquals(setOf(7), board.widgetIds)
        assertEquals(emptySet<Int>(), board.layout.widgetIds)
    }

    @Test fun `a move that can't happen changes nothing`() {
        val board = Board(HomeLayout().addWidget(7, 2), Pages().addPage())
        assertSame(board, board.moveWidgetToPage(7, 3))
        assertSame(board, board.moveWidgetToPage(8, 0))
        assertSame(board, board.moveWidgetToMain(7))
        val full = Board(board.layout, (1..Pages.MAX_ITEMS).fold(board.pages) { p, n -> p.add(0, app(n)) })
        assertSame(full, full.moveWidgetToPage(7, 0))
    }

    @Test fun `concurrent widget moves and page edits keep every widget in exactly one place`() = runBlocking {
        val s = open()
        s.pages.updateBoard { Board((1..10).fold(it.layout) { l, id -> l.addWidget(id, 1) }, it.pages.addPage().addPage()) }
        (1..10).map { id ->
            async(Dispatchers.IO) { s.pages.updateBoard { it.moveWidgetToPage(id, id % 2) } }
        }.awaitAll()
        val board = s.pages.board.first()
        assertEquals((1..10).toSet(), board.pages.widgetIds)
        assertEquals(emptySet<Int>(), board.layout.widgetIds)
        assertEquals(10, board.pages.pages.sumOf { it.size })
    }
}
