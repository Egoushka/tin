package io.github.egoushka.tin.home

import io.github.egoushka.tin.home.pages.FlowRow
import io.github.egoushka.tin.home.pages.PageItem
import io.github.egoushka.tin.home.pages.Pages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PagesTest {
    private fun app(n: Int) = PageItem.App("com.example.$n/Main#0")
    private fun ids(pages: Pages, page: Int) = pages.pages[page].map { it.id }
    private val two = Pages().addPage().addPage()

    @Test fun `up to five extra pages, a sixth changes nothing`() {
        val five = (1..Pages.MAX_EXTRA).fold(Pages()) { p, _ -> p.addPage() }
        assertEquals(5, five.count)
        assertSame(five, five.addPage())
    }

    @Test fun `removing a page drops it and its items, a bad index changes nothing`() {
        val p = two.add(0, app(1)).add(1, app(2))
        assertEquals(listOf(app(2).id), ids(p.removePage(0), 0))
        assertEquals(1, p.removePage(0).count)
        assertSame(p, p.removePage(2))
        assertSame(p, p.removePage(-1))
    }

    @Test fun `add appends in order`() {
        val p = two.add(0, app(1)).add(0, app(2)).add(0, app(3))
        assertEquals(listOf(app(1), app(2), app(3)).map { it.id }, ids(p, 0))
    }

    @Test fun `the same thing twice on a page is ignored, on another page it is fine`() {
        val p = two.add(0, app(1))
        assertSame(p, p.add(0, app(1)))
        assertEquals(1, p.add(1, app(1)).pages[1].size)
    }

    @Test fun `add to a missing page or a full page changes nothing`() {
        assertSame(two, two.add(2, app(1)))
        val full = (1..Pages.MAX_ITEMS).fold(Pages().addPage()) { p, n -> p.add(0, app(n)) }
        assertEquals(Pages.MAX_ITEMS, full.pages[0].size)
        assertSame(full, full.add(0, app(Pages.MAX_ITEMS + 1)))
    }

    @Test fun `move within a page puts the item at the index among the others`() {
        val p = two.add(0, app(1)).add(0, app(2)).add(0, app(3)).add(0, app(4))
        assertEquals(listOf(2, 3, 1, 4).map { app(it).id }, ids(p.move(0, app(1).id, 0, 2), 0))
        assertEquals(listOf(4, 1, 2, 3).map { app(it).id }, ids(p.move(0, app(4).id, 0, 0), 0))
    }

    @Test fun `move across pages leaves the source without it`() {
        val p = two.add(0, app(1)).add(0, app(2)).add(1, app(3)).move(0, app(1).id, 1, 0)
        assertEquals(listOf(app(2).id), ids(p, 0))
        assertEquals(listOf(app(1), app(3)).map { it.id }, ids(p, 1))
    }

    @Test fun `the index is clamped`() {
        val p = two.add(0, app(1)).add(1, app(2)).add(1, app(3))
        assertEquals(listOf(app(2), app(3), app(1)).map { it.id }, ids(p.move(0, app(1).id, 1, 99), 1))
        assertEquals(listOf(app(1), app(2), app(3)).map { it.id }, ids(p.move(0, app(1).id, 1, -5), 1))
    }

    @Test fun `a bad page or item changes nothing`() {
        val p = two.add(0, app(1))
        assertSame(p, p.move(0, "app:nope", 1, 0))
        assertSame(p, p.move(0, app(1).id, 5, 0))
        assertSame(p, p.move(5, app(1).id, 0, 0))
    }

    @Test fun `a move onto a page that already has the same app changes nothing`() {
        val p = two.add(0, app(1)).add(1, app(1))
        assertSame(p, p.move(0, app(1).id, 1, 0))
    }

    @Test fun `moveBefore lands in front of the named item or at the end`() {
        val p = two.add(0, app(1)).add(1, app(2)).add(1, app(3))
        assertEquals(listOf(app(2), app(1), app(3)).map { it.id }, ids(p.moveBefore(0, app(1).id, 1, app(3).id), 1))
        assertEquals(listOf(app(2), app(3), app(1)).map { it.id }, ids(p.moveBefore(0, app(1).id, 1, null), 1))
        assertEquals(listOf(app(2), app(3), app(1)).map { it.id }, ids(p.moveBefore(0, app(1).id, 1, "app:gone"), 1))
    }

    @Test fun `step moves one place and is a no-op at the ends`() {
        val p = two.add(0, app(1)).add(0, app(2))
        assertEquals(listOf(app(2), app(1)).map { it.id }, ids(p.step(0, app(1).id, 1), 0))
        assertSame(p, p.step(0, app(1).id, -1))
        assertSame(p, p.step(0, app(2).id, 1))
    }

    @Test fun `remove takes one item off, an unknown one changes nothing`() {
        val p = two.add(0, app(1)).add(0, app(2))
        assertEquals(listOf(app(2).id), ids(p.remove(0, app(1).id), 0))
        assertSame(p, p.remove(0, "app:nope"))
        assertSame(p, p.remove(7, app(1).id))
    }

    @Test fun `pageOf finds the page that holds an item`() {
        val p = two.add(1, app(1))
        assertEquals(1, p.pageOf(app(1).id))
        assertNull(p.pageOf(app(2).id))
    }

    @Test fun `widgets in use span all pages, resizing stays within the main rule`() {
        val p = two.add(0, PageItem.Widget(11, 2)).add(1, PageItem.Widget(12, 3)).add(1, app(1))
        assertEquals(setOf(11, 12), p.widgetIds)
        assertEquals(PageItem.Widget(12, 8), p.setRows(12, 99).pages[1][0])
        assertEquals(PageItem.Widget(12, 1), p.setRows(12, 0).pages[1][0])
        assertSame(p, p.setRows(99, 2))
    }

    @Test fun `reconcile drops widgets the host no longer has and keeps the rest`() {
        val p = two.add(0, PageItem.Widget(11, 2)).add(0, app(1)).add(1, PageItem.Widget(12, 2))
        val kept = p.reconcile(setOf(12))
        assertEquals(listOf(app(1).id), ids(kept, 0))
        assertEquals(setOf(12), kept.widgetIds)
        assertSame(p, p.reconcile(setOf(11, 12)))
    }

    @Test fun `shortcut ids per app and user, over all pages`() {
        val p = two
            .add(0, PageItem.Shortcut("a", "s1", 0, "One"))
            .add(1, PageItem.Shortcut("a", "s2", 0, "Two"))
            .add(1, PageItem.Shortcut("a", "s3", 10, "Work"))
            .add(1, PageItem.Shortcut("b", "s1", 0, "Other"))
        assertEquals(setOf("s1", "s2"), p.shortcutIds("a", 0))
        assertEquals(setOf("s3"), p.shortcutIds("a", 10))
        assertEquals(emptySet<String>(), p.shortcutIds("c", 0))
    }

    @Test fun `flow puts four tiles to a row and a widget alone in its row`() {
        val a = app(1); val b = app(2); val c = app(3); val d = app(4); val e = app(5)
        val w = PageItem.Widget(11, 2)
        assertEquals(emptyList<FlowRow>(), Pages.flow(emptyList()))
        assertEquals(listOf(FlowRow.Tiles(listOf(a, b, c, d)), FlowRow.Tiles(listOf(e))), Pages.flow(listOf(a, b, c, d, e)))
        assertEquals(
            listOf(FlowRow.Tiles(listOf(a, b)), FlowRow.Wide(w), FlowRow.Tiles(listOf(c, d, e))),
            Pages.flow(listOf(a, b, w, c, d, e)),
        )
        assertEquals(listOf(FlowRow.Wide(w), FlowRow.Wide(PageItem.Widget(12, 1))), Pages.flow(listOf(w, PageItem.Widget(12, 1))))
    }
}
