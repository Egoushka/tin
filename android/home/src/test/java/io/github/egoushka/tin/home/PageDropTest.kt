package io.github.egoushka.tin.home

import io.github.egoushka.tin.home.pages.Edge
import io.github.egoushka.tin.home.pages.PageDrop
import io.github.egoushka.tin.home.pages.Spot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageDropTest {
    // Two rows of three 100-wide tiles with a 10 gap: row 0 at y 0..100, row 1 at y 110..210.
    private fun tile(n: Int): Spot {
        val col = n % 3
        val row = n / 3
        return Spot("t$n", col * 110f, row * 110f, col * 110f + 100f, row * 110f + 100f)
    }

    private val spots = (0..5).map(::tile)

    @Test fun `left half of a tile goes before it, right half after it`() {
        assertEquals("t1", PageDrop.before(spots, "t4", x = 120f, y = 50f))
        assertEquals("t2", PageDrop.before(spots, "t4", x = 180f, y = 50f))
    }

    @Test fun `between tiles in a row goes by which side of the middle`() {
        assertEquals("t1", PageDrop.before(spots, "t4", x = 105f, y = 50f))
        assertEquals("t1", PageDrop.before(spots, "t4", x = 109f, y = 50f))
    }

    @Test fun `between rows goes after the row above`() {
        assertEquals("t3", PageDrop.before(spots, "t5", x = 30f, y = 105f))
        assertEquals("t3", PageDrop.before(spots, "t5", x = 250f, y = 105f))
    }

    @Test fun `below everything is the end, above everything is the start`() {
        assertNull(PageDrop.before(spots, "t0", x = 50f, y = 900f))
        assertEquals("t0", PageDrop.before(spots, "t5", x = 50f, y = -40f))
    }

    @Test fun `past the right edge of a row is after its last tile`() {
        assertEquals("t3", PageDrop.before(spots, "t5", x = 400f, y = 50f))
    }

    @Test fun `an empty page has no item to land before`() {
        assertNull(PageDrop.before(emptyList(), "t0", x = 10f, y = 10f))
    }

    @Test fun `the dragged item is skipped`() {
        assertEquals("t2", PageDrop.before(spots, "t1", x = 120f, y = 50f))
        assertNull(PageDrop.before(listOf(tile(0)), "t0", x = 10f, y = 10f))
    }

    @Test fun `a wide widget row is passed past its middle`() {
        val rows = listOf(Spot("a", 0f, 0f, 330f, 100f), Spot("b", 0f, 110f, 330f, 210f))
        assertEquals("a", PageDrop.before(rows, "z", x = 100f, y = 50f))
        assertEquals("b", PageDrop.before(rows, "z", x = 200f, y = 50f))
        assertNull(PageDrop.before(rows, "z", x = 200f, y = 150f))
    }

    @Test fun `the edge zone is 48 dp at either side and nothing in between`() {
        val zone = 48f
        assertEquals(Edge.Left, PageDrop.edge(0f, 400f, zone))
        assertEquals(Edge.Left, PageDrop.edge(47f, 400f, zone))
        assertEquals(Edge.None, PageDrop.edge(48f, 400f, zone))
        assertEquals(Edge.None, PageDrop.edge(352f, 400f, zone))
        assertEquals(Edge.Right, PageDrop.edge(353f, 400f, zone))
        assertEquals(Edge.Right, PageDrop.edge(400f, 400f, zone))
    }

    @Test fun `the hold is 600 ms and the zone 48 dp`() {
        assertEquals(600L, PageDrop.HOLD_MS)
        assertEquals(48, PageDrop.EDGE_DP)
    }
}
