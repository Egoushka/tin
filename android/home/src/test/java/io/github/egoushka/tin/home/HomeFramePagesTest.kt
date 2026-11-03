package io.github.egoushka.tin.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.egoushka.tin.home.layout.FrameTags
import io.github.egoushka.tin.home.layout.HomeFrame
import io.github.egoushka.tin.home.pages.PageDots
import io.github.egoushka.tin.home.pages.PageTags
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.theme.NgType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home's frame with a main page and two more: sideways turns the page, up and down still judge the page on screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h914dp-420dpi")
class HomeFramePagesTest {
    @get:Rule val rule = createComposeRule()

    private var up = 0
    private var down = 0
    private var fontScale by mutableFloatStateOf(1f)
    private lateinit var pager: PagerState
    private lateinit var scope: CoroutineScope

    @Before fun open() {
        rule.setContent {
            pager = rememberPagerState { 3 }
            scope = rememberCoroutineScope()
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                HomeFrame(
                    gap = 12.dp,
                    gestures = true,
                    onSwipeUp = { up++ },
                    onSwipeDown = { down++ },
                    onDoubleTap = {},
                    onLongPress = {},
                    top = { Text("12:00", style = NgType.clock, modifier = Modifier.testTag("top")) },
                    bottom = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.testTag("pins").fillMaxWidth().height(Dimen.tile))
                            Box(Modifier.testTag("dock").fillMaxWidth().height(Dimen.tile))
                            Box(Modifier.testTag("search").fillMaxWidth().height(Dimen.tile))
                        }
                    },
                    pager = pager,
                    dots = { PageDots(pager, 2, editing = false) {} },
                    // The first extra page is short, the second long enough to scroll.
                    page = { i -> repeat(if (i == 0) 1 else 12) { j -> Box(Modifier.testTag("page$i.card$j").fillMaxWidth().height(160.dp)) } },
                    stack = { Box(Modifier.testTag("main").fillMaxWidth().height(160.dp)) },
                )
            }
        }
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).getBoundsInRoot()

    private fun shown() = rule.runOnIdle { pager.currentPage }

    private fun slide(fromX: Float, fromY: Float, toX: Float, toY: Float, millis: Long) = rule.onRoot().performTouchInput {
        swipe(Offset(fromX.dp.toPx(), fromY.dp.toPx()), Offset(toX.dp.toPx(), toY.dp.toPx()), durationMillis = millis)
    }.also { rule.waitForIdle() }

    private fun midStackY(): Float = bounds(FrameTags.Stack).let { (it.top.value + it.bottom.value) / 2 }

    private fun goTo(page: Int) { rule.runOnIdle { scope.launch { pager.scrollToPage(page) } }; rule.waitForIdle() }

    @Test fun `the dots say which page this is and move with it`() {
        rule.onNodeWithContentDescription("Page 1 of 3").assertIsDisplayed()
        slide(360f, midStackY(), 40f, midStackY(), millis = 150)
        assertEquals(1, shown())
        rule.onNodeWithContentDescription("Page 2 of 3").assertIsDisplayed()
    }

    @Test fun `a sideways swipe turns the page and opens no layer`() {
        slide(360f, midStackY(), 40f, midStackY(), millis = 150)
        assertEquals(1, shown())
        assertEquals(0 to 0, up to down)
    }

    @Test fun `a swipe that is more sideways than up turns the page and does not open the drawer`() {
        val y = midStackY()
        slide(360f, y, 100f, y - 100f, millis = 150)
        assertEquals(1, shown())
        assertEquals(0, up)
    }

    @Test fun `a mostly vertical swipe on a page opens the drawer and leaves the page`() {
        val y = midStackY()
        slide(300f, y, 240f, y - 160f, millis = 400)
        assertEquals(0, shown())
        assertEquals(1, up)
    }

    @Test fun `a vertical swipe from the 24 dp edge is left to Android's back gesture`() {
        goTo(1)
        val y = midStackY()
        slide(10f, y, 10f, y - 150f, millis = 800)
        slide(402f, y, 402f, y + 150f, millis = 800)
        assertEquals(0 to 0, up to down)
    }

    @Test fun `on a page that scrolls, up opens the drawer only at its end`() {
        goTo(2)
        val y = midStackY()
        slide(206f, y, 206f, y - 150f, millis = 800)
        assertEquals("at the top of a long page a swipe up only scrolls", 0, up)
        rule.onNodeWithTag("page1.card11").performScrollTo()
        slide(206f, y, 206f, y - 150f, millis = 800)
        assertEquals(1, up)
    }

    @Test fun `in the middle of a long page swipes only scroll it`() {
        goTo(2)
        rule.onNodeWithTag("page1.card6").performScrollTo()
        val y = midStackY()
        slide(206f, y, 206f, y - 100f, millis = 800)
        slide(206f, y, 206f, y + 100f, millis = 800)
        assertEquals(0 to 0, up to down)
    }

    @Test fun `each page keeps its own scroll`() {
        goTo(2)
        rule.onNodeWithTag("page1.card11").performScrollTo()
        goTo(1)
        rule.onNodeWithTag("page0.card0").assertIsDisplayed()
        goTo(2)
        rule.onNodeWithTag("page1.card11").assertIsDisplayed()
        goTo(0)
        rule.onNodeWithTag("main").assertIsDisplayed()
    }

    @Test fun `the dots, the dock and the search bar stay on screen on every page at both font scales`() {
        for (page in 0..2) for (scale in listOf(1f, 1.3f)) {
            fontScale = scale
            goTo(page)
            val root = rule.onRoot().getBoundsInRoot()
            rule.onNodeWithTag(PageTags.Dots).assertIsDisplayed()
            rule.onNodeWithTag("dock").assertIsDisplayed()
            rule.onNodeWithTag("search").assertIsDisplayed()
            assert(bounds("search").bottom <= root.bottom) { "search bar below the screen on page $page at $scale" }
            assert(bounds(FrameTags.Stack).bottom <= bounds(PageTags.Dots).top) { "pages run under the dots on page $page at $scale" }
            assert(bounds(PageTags.Dots).bottom <= bounds("pins").top) { "dots run under the pins on page $page at $scale" }
            assert(bounds("dock").bottom <= bounds("search").top) { "dock and search overlap on page $page at $scale" }
        }
    }
}
