package io.github.egoushka.tin.home

import io.github.egoushka.tin.home.pages.PageItem
import io.github.egoushka.tin.home.pages.Pages
import org.junit.Assert.assertEquals
import org.junit.Test

class PagesCodecTest {
    private fun roundTrip(p: Pages) = Pages.decode(p.encode())

    @Test fun `every item kind survives a round trip`() {
        val p = Pages(listOf(
            listOf(PageItem.App("com.example/.Main#0"), PageItem.Shortcut("com.example", "compose", 10, "Write"), PageItem.Widget(11, 3)),
            emptyList(),
            listOf(PageItem.App("org.other/.A#10")),
        ))
        assertEquals(p, roundTrip(p))
    }

    @Test fun `separators, percent signs and non-ASCII in ids and labels survive`() {
        val p = Pages(listOf(listOf(
            PageItem.App("a,b;c|d%e/.Main#0"),
            PageItem.Shortcut("p,q", "id;1|%25", 0, "Нова, «чернетка»; 100% | ok\nline"),
            PageItem.Shortcut("a%2Cb", "x", 0, "%7C"),
        )))
        assertEquals(p, roundTrip(p))
    }

    @Test fun `no key, an empty string and a lone separator are no extra pages`() {
        assertEquals(Pages(), Pages.decode(null))
        assertEquals(Pages(), Pages.decode(""))
        assertEquals(Pages(emptyList()), Pages().let { roundTrip(it) })
    }

    @Test fun `empty pages are kept`() {
        val p = Pages().addPage().addPage()
        assertEquals(2, roundTrip(p).count)
    }

    @Test fun `a corrupt item is skipped and the rest of its page is kept`() {
        val raw = "a|one,zzz,w|x|2,w|7|0,s|only|three|fields,w|8|2,a|;a|two;"
        val pages = Pages.decode(raw)
        assertEquals(listOf("app:one", "widget:8"), pages.pages[0].map { it.id })
        assertEquals(listOf("app:two"), pages.pages[1].map { it.id })
    }

    @Test fun `a corrupt page does not take the others with it`() {
        val pages = Pages.decode("garbage;a|ok;|||,,,;w|5|1;")
        assertEquals(4, pages.count)
        assertEquals(listOf("app:ok"), pages.pages[1].map { it.id })
        assertEquals(setOf(5), pages.widgetIds)
    }

    @Test fun `a repeat on a page is dropped, a widget height is capped`() {
        val pages = Pages.decode("a|x,a|x,w|3|99;")
        assertEquals(listOf(PageItem.App("x"), PageItem.Widget(3, 8)), pages.pages[0])
    }

    @Test fun `more pages and items than the caps are cut`() {
        val manyPages = (1..40).joinToString("") { "a|p$it;" }
        assertEquals(Pages.MAX_EXTRA, Pages.decode(manyPages).count)
        val manyItems = (1..500).joinToString(",") { "a|i$it" } + ";"
        assertEquals(Pages.MAX_ITEMS, Pages.decode(manyItems).pages[0].size)
    }

    @Test fun `a hostile megabyte decodes without throwing`() {
        val noise = "%;|,%2%zz;,a|".repeat(80_000)
        Pages.decode(noise)
        Pages.decode("a|" + "%".repeat(1_000_000))
        Pages.decode(";".repeat(1_000_000))
        Pages.decode("w|" + "9".repeat(1_000_000) + "|1")
    }

    @Test fun `a negative or non-numeric widget id or user is rejected`() {
        assertEquals(0, Pages.decode("w|-1|2;").widgetIds.size)
        assertEquals(0, Pages.decode("w|abc|2;").widgetIds.size)
        assertEquals(0, Pages.decode("s|p|id|x|label;").pages[0].size)
    }
}
