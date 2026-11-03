package io.github.egoushka.tin.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.egoushka.tin.home.layout.HomeState
import io.github.egoushka.tin.home.layout.LayoutStore
import io.github.egoushka.tin.home.layout.PinStore
import io.github.egoushka.tin.home.layout.WidgetHost
import io.github.egoushka.tin.home.pages.PageDrag
import io.github.egoushka.tin.home.pages.PageDrop
import io.github.egoushka.tin.home.pages.PageItem
import io.github.egoushka.tin.home.pages.PageStore
import io.github.egoushka.tin.home.pages.Pages
import io.github.egoushka.tin.home.pages.pageDrag
import io.github.egoushka.tin.home.pages.pageSpot
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real long-press-and-drag over a real [HomeState] on a file-backed store: two extra pages of plain tiles, four to a row
 * as the app tiles flow, each reporting itself with [pageSpot]. The tile and the sheet are not drawn here; they need an app list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h914dp-420dpi")
class PagesUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val dir = File.createTempFile("tin-pages-ui", "").apply { delete(); mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drag = PageDrag()
    private val buzz = Buzz()
    private lateinit var home: HomeState
    private lateinit var pager: PagerState
    private lateinit var composition: CoroutineScope

    private class Buzz : HapticFeedback {
        var count = 0
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { count++ }
    }

    private fun app(n: String) = PageItem.App(n)

    /** Page 0 holds a0 to a5 (a row of four, then two), page 1 holds b0 and b1. */
    private val seed = listOf("a0", "a1", "a2", "a3", "a4", "a5").fold(Pages().addPage().addPage()) { p, n -> p.add(0, app(n)) }
        .add(1, app("b0")).add(1, app("b1"))

    @Before fun open() {
        val ds = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "tin.tune.preferences_pb") }
        val pages = PageStore(ds)
        runBlocking { pages.update { seed } }
        home = HomeState(LayoutStore(ds), pages, PinStore(ds), WidgetHost(rule.activity), scope)
        home.edit(true)
        rule.waitUntil(5_000) { home.pages == seed }
        rule.setContent {
            pager = rememberPagerState { 1 + home.pages.count }
            composition = rememberCoroutineScope()
            val flip = rememberCoroutineScope()
            Box(Modifier.fillMaxSize().pageDrag(drag, home, pager, flip, buzz, enabled = home.editing)) {
                HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = drag.lifted == null) { index ->
                    if (index == 0) {
                        Box(Modifier.fillMaxSize().testTag("main"))
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            home.pages.pages.getOrNull(index - 1).orEmpty().chunked(Pages.COLUMNS).forEach { row ->
                                Row(Modifier.fillMaxWidth()) {
                                    row.forEach { item ->
                                        key(item.id) { Box(Modifier.weight(1f).height(80.dp).testTag(item.id).pageSpot(drag, index - 1, item.id)) }
                                    }
                                    repeat(Pages.COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        show(1)
    }

    @After fun close() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun show(page: Int) {
        rule.runOnIdle { composition.launch { pager.scrollToPage(page) } }
        rule.waitForIdle()
    }

    private fun at(id: String, x: Float = 0.5f, y: Float = 0.5f): Offset {
        val r = rule.onNodeWithTag(id).fetchSemanticsNode().boundsInRoot
        return Offset(r.left + r.width * x, r.top + r.height * y)
    }

    private fun press(from: Offset) {
        rule.onRoot().performTouchInput { down(from) }
        rule.mainClock.advanceTimeBy(700)
        rule.waitForIdle()
    }

    private fun moveTo(to: Offset) {
        rule.onRoot().performTouchInput { moveTo(to) }
        rule.waitForIdle()
    }

    private fun letGo() {
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
    }

    private fun order(page: Int) = home.pages.pages[page].map { it.id.removePrefix("app:") }

    /** The store answers on another thread, so wait for the order to settle before it is compared. */
    private fun settled(page: Int, expected: List<String>) {
        runCatching { rule.waitUntil(5_000) { home.pages.pages.getOrNull(page)?.map { it.id.removePrefix("app:") } == expected } }
        assertEquals(expected, order(page))
    }

    @Test fun `a long press lifts the tile with a buzz and dropping it past the fourth tile puts it there`() {
        press(at("app:a0"))
        assertEquals("app:a0", drag.lifted?.id)
        assertEquals(1, buzz.count)
        moveTo(at("app:a3", x = 0.8f))
        letGo()
        assertNull(drag.lifted)
        settled(0, listOf("a1", "a2", "a3", "a0", "a4", "a5"))
    }

    @Test fun `the drag and the Move later action end in the same order`() {
        press(at("app:a0"))
        moveTo(at("app:a2", x = 0.2f))
        letGo()
        settled(0, seed.step(0, "app:a0", 1).pages[0].map { it.id.removePrefix("app:") })
    }

    @Test fun `a drop on empty space goes to the end of the page`() {
        press(at("app:a1"))
        moveTo(Offset(at("app:a5").x, at("app:a5").y + 400f))
        letGo()
        settled(0, listOf("a0", "a2", "a3", "a4", "a5", "a1"))
    }

    @Test fun `held at the right edge the page turns and the drop lands on the next page`() {
        val edge = Offset(rule.onRoot().fetchSemanticsNode().boundsInRoot.right - 4f, at("app:a0").y)
        press(at("app:a0"))
        moveTo(edge)
        rule.mainClock.advanceTimeBy(PageDrop.HOLD_MS + 100)
        rule.waitForIdle()
        assertEquals(2, rule.runOnIdle { pager.currentPage })
        assertEquals(1, drag.over)
        moveTo(at("app:b1", x = 0.2f))
        letGo()
        settled(1, listOf("b0", "a0", "b1"))
        settled(0, listOf("a1", "a2", "a3", "a4", "a5"))
    }

    @Test fun `a tap and a swipe do not lift anything`() {
        rule.onRoot().performTouchInput { down(at("app:a0")); up() }
        rule.waitForIdle()
        assertNull(drag.lifted)
        rule.onRoot().performTouchInput { down(at("app:a0")); moveTo(at("app:a2")); up() }
        rule.waitForIdle()
        assertNull(drag.lifted)
        assertEquals(0, buzz.count)
        assertEquals(listOf("a0", "a1", "a2", "a3", "a4", "a5"), order(0))
    }

    @Test fun `a long press on empty space or outside edit mode lifts nothing`() {
        press(Offset(at("app:a5").x, at("app:a5").y + 400f))
        assertNull(drag.lifted)
        letGo()
        rule.runOnIdle { home.edit(false) }
        rule.waitForIdle()
        press(at("app:a0"))
        assertNull(drag.lifted)
        letGo()
        assertEquals(0, buzz.count)
        assertEquals(listOf("a0", "a1", "a2", "a3", "a4", "a5"), order(0))
    }
}
