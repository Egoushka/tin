package io.github.egoushka.tin.home

import io.github.egoushka.tin.home.layout.CardId
import io.github.egoushka.tin.home.layout.HomeLayout
import io.github.egoushka.tin.home.layout.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {
    private fun card(id: CardId) = Slot.Card(id)

    @Test fun `default is the six cards in slice one order, nothing hidden`() {
        val l = HomeLayout()
        assertEquals(
            listOf(CardId.NeedsYou, CardId.Today, CardId.Glance, CardId.Music, CardId.Alarm, CardId.Homelab).map(::card),
            l.visible,
        )
        assertTrue(l.hidden.isEmpty())
    }

    @Test fun `move swaps with the nearest visible neighbour and clamps at the ends`() {
        val l = HomeLayout()
        assertEquals(l.visible[1], l.move(l.visible[0], 1).visible[0])
        assertEquals(l, l.move(l.visible.first(), -1))
        assertEquals(l, l.move(l.visible.last(), 1))
        assertEquals(l, l.move(Slot.Widget(9), 1))
    }

    @Test fun `move steps over hidden cards and leaves them where they were`() {
        val l = HomeLayout().hide(card(CardId.Today))
        val moved = l.move(card(CardId.NeedsYou), 1)
        assertEquals(card(CardId.Glance), moved.visible[0])
        assertEquals(card(CardId.NeedsYou), moved.visible[1])
        // Today still sits where it was between the two positions that swapped around it.
        assertEquals(
            listOf(CardId.Glance, CardId.Today, CardId.NeedsYou).map(::card),
            moved.order.take(3),
        )
    }

    @Test fun `a hidden card comes back in its old place`() {
        val l = HomeLayout().hide(card(CardId.Glance))
        assertEquals(5, l.visible.size)
        assertEquals(listOf(card(CardId.Glance)), l.hiddenSlots)
        assertEquals(HomeLayout(), l.show(card(CardId.Glance)))
    }

    @Test fun `a widget can be removed but not hidden`() {
        val l = HomeLayout().addWidget(7, 2)
        assertEquals(l, l.hide(Slot.Widget(7)))
        val gone = l.removeWidget(7)
        assertEquals(HomeLayout(), gone)
        assertEquals(gone, gone.removeWidget(7))
    }

    @Test fun `a widget joins the end with its height, and a repeat is ignored`() {
        val l = HomeLayout().addWidget(7, 3)
        assertEquals(Slot.Widget(7), l.visible.last())
        assertEquals(3, l.rows[7])
        assertEquals(l, l.addWidget(7, 5))
    }

    @Test fun `rows are clamped and only apply to a widget in the layout`() {
        val l = HomeLayout().addWidget(7, 2)
        assertEquals(HomeLayout.MAX_ROWS, l.setRows(7, 99).rows[7])
        assertEquals(1, l.setRows(7, 0).rows[7])
        assertEquals(l, l.setRows(8, 3))
    }

    @Test fun `height follows the stock cell rule`() {
        assertEquals(40, HomeLayout.heightDp(1))
        assertEquals(250, HomeLayout.heightDp(4))
        assertEquals(1, HomeLayout.rowsFor(40))
        assertEquals(2, HomeLayout.rowsFor(41))
        assertEquals(HomeLayout.MAX_ROWS, HomeLayout.rowsFor(5000))
    }

    @Test fun `encode then decode is the identity`() {
        val l = HomeLayout().addWidget(7, 3).addWidget(12, 2)
            .hide(card(CardId.Music)).move(card(CardId.Homelab), -2).move(Slot.Widget(12), -1)
        assertEquals(l, HomeLayout.decode(l.encodeOrder(), l.encodeHidden(), l.encodeRows()))
    }

    @Test fun `decode drops unknown, repeated and malformed entries and appends a missing card`() {
        val l = HomeLayout.decode(
            order = "card:homelab, nonsense,card:homelab,widget:x,widget:-3,widget:5,card:future,card:today",
            hidden = "card:today,widget:5,card:unknown,",
            rows = "5:2,5:x,6:3,7,5:0,:4",
        )
        assertEquals(
            listOf(card(CardId.Homelab), Slot.Widget(5), card(CardId.Today)) +
                listOf(CardId.NeedsYou, CardId.Glance, CardId.Music, CardId.Alarm).map(::card),
            l.order,
        )
        assertEquals(setOf<Slot>(card(CardId.Today)), l.hidden)
        assertEquals(mapOf(5 to 2), l.rows)
    }

    @Test fun `empty or garbage input is the default`() {
        assertEquals(HomeLayout(), HomeLayout.decode(null, null, null))
        assertEquals(HomeLayout(), HomeLayout.decode("", "", ""))
        assertEquals(HomeLayout(), HomeLayout.decode("???", ",,,", "::"))
    }

    @Test fun `reconcile drops widgets the host no longer knows`() {
        val l = HomeLayout().addWidget(1, 2).addWidget(2, 2)
        val r = l.reconcile(setOf(2))
        assertEquals(setOf(2), r.widgetIds)
        assertEquals(mapOf(2 to 2), r.rows)
        assertEquals(l, l.reconcile(setOf(1, 2, 3)))
    }

    @Test fun `a widget that does not resize vertically keeps exactly its own rows`() {
        // A 110 dp tall widget needs two rows (70 * 2 - 30 = 110).
        assertEquals(2..2, HomeLayout.rowRange(110, 0, resizesVertically = false))
        assertEquals(1..1, HomeLayout.rowRange(40, 0, resizesVertically = false))
    }

    @Test fun `a widget that resizes may grow to the stack's limit from its smallest size`() {
        assertEquals(2..HomeLayout.MAX_ROWS, HomeLayout.rowRange(250, 110, resizesVertically = true))
        // No declared minimum resize: its minimum height is the floor.
        assertEquals(3..HomeLayout.MAX_ROWS, HomeLayout.rowRange(180, 0, resizesVertically = true))
    }

    @Test fun `rows are clamped to the stack`() {
        assertEquals(HomeLayout.MAX_ROWS, HomeLayout.rowsFor(5000))
        assertEquals(1, HomeLayout.rowsFor(0))
        assertEquals(40, HomeLayout.heightDp(1))
    }
}
