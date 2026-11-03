package io.github.egoushka.tin.home

import android.content.ComponentName
import io.github.egoushka.tin.home.layout.HomeLayout
import io.github.egoushka.tin.home.layout.WidgetBackend
import io.github.egoushka.tin.home.layout.WidgetLifecycle
import io.github.egoushka.tin.home.pages.Board
import io.github.egoushka.tin.home.pages.PageItem
import io.github.egoushka.tin.home.pages.Pages
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetsInUseTest {
    private class Host(vararg ids: Int) : WidgetBackend {
        val live = ids.toMutableSet()
        override fun allocate(): Int? = null
        override fun delete(id: Int) { live -= id }
        override fun liveIds(): Set<Int> = live.toSet()
        override fun bind(id: Int, provider: ComponentName) = true
        override fun needsConfigure(id: Int) = false
    }

    private val board = Board(HomeLayout().addWidget(1, 2), Pages().addPage().add(0, PageItem.Widget(2, 3)))

    @Test fun `the board lists the widgets of the stack and of every page`() {
        assertEquals(setOf(1, 2), board.widgetIds)
    }

    @Test fun `the sweep keeps the ids of both the layout and the pages and deletes the rest`() {
        val host = Host(1, 2, 3)
        val strays = WidgetLifecycle(host).sweep(board.widgetIds)
        assertEquals(setOf(3), strays)
        assertEquals(setOf(1, 2), host.live)
    }

    @Test fun `a sweep that only knew the layout would have deleted the page widget`() {
        val host = Host(1, 2)
        assertEquals(setOf(2), WidgetLifecycle(host).sweep(board.layout.widgetIds))
    }

    @Test fun `reconcile drops what the host lost from the stack and the pages alike`() {
        val kept = board.reconcile(setOf(2))
        assertEquals(emptySet<Int>(), kept.layout.widgetIds)
        assertEquals(setOf(2), kept.pages.widgetIds)
        assertEquals(emptySet<Int>(), board.reconcile(emptySet()).widgetIds)
    }
}
