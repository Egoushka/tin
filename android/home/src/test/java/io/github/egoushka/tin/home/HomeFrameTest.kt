package io.github.egoushka.tin.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.advanceEventTime
import androidx.compose.ui.test.down
import androidx.compose.ui.test.move
import androidx.compose.ui.test.up
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.egoushka.tin.home.layout.FrameTags
import io.github.egoushka.tin.home.layout.HomeFrame
import io.github.egoushka.tin.home.layout.noHomeGestures
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.theme.NgType
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home's frame on a Pixel 8a sized screen: nothing clips, the dock stays put, swipes only count where they should. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h914dp-420dpi")
class HomeFrameTest {
    @get:Rule val rule = createComposeRule()

    private var up = 0
    private var down = 0
    private var long = 0
    private var twoUp = 0
    private var twoDown = 0
    private var pinch = 0
    private var double = 0
    private var cards by mutableIntStateOf(0)
    private var fontScale by mutableFloatStateOf(1f)
    private var gestures by mutableStateOf(true)
    private var zone by mutableStateOf(false)

    @Before fun open() {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                HomeFrame(
                    gap = 12.dp,
                    gestures = gestures,
                    onSwipeUp = { up++ },
                    onSwipeDown = { down++ },
                    onLongPress = { long++ },
                    onTwoFingerUp = { twoUp++ },
                    onTwoFingerDown = { twoDown++ },
                    onPinchIn = { pinch++ },
                    onDoubleTap = { double++ },
                    top = { Text("12:00", style = NgType.clock, modifier = Modifier.testTag("top")) },
                    bottom = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.testTag("pins").fillMaxWidth().height(Dimen.tile))
                            Box(Modifier.testTag("dock").fillMaxWidth().height(Dimen.tile))
                            Box(Modifier.testTag("search").fillMaxWidth().height(Dimen.tile))
                        }
                    },
                    stack = {
                        repeat(cards) { i -> Box(Modifier.testTag("card$i").fillMaxWidth().height(160.dp)) { Text("card $i") } }
                        if (zone) Box(Modifier.testTag("zone").fillMaxWidth().height(160.dp).noHomeGestures())
                    },
                )
            }
        }
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).getBoundsInRoot()

    private fun settle(block: () -> Unit) { block(); rule.waitForIdle() }

    private fun scrollStackBy(px: Float) = rule.onNodeWithTag(FrameTags.Stack).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, px) }

    /** A slow vertical drag, so it is a scroll and not a fling; positive [dy] moves the finger down. */
    private fun drag(xDp: Float, yDp: Float, dy: Float) = rule.onRoot().performTouchInput {
        val from = Offset(xDp.dp.toPx(), yDp.dp.toPx())
        swipe(from, Offset(from.x, from.y + dy.dp.toPx()), durationMillis = 800)
    }

    private fun midStack(): Pair<Float, Float> {
        val b = bounds(FrameTags.Stack)
        return 206f to (b.top.value + b.bottom.value) / 2
    }

    private fun swipesSeen() = up to down

    @Test fun `the dock and the search bar stay on screen with any number of cards at both font scales`() {
        for (n in listOf(0, 6, 12)) for (scale in listOf(1f, 1.3f)) {
            settle { cards = n; fontScale = scale }
            val root = rule.onRoot().getBoundsInRoot()
            rule.onNodeWithTag("dock").assertIsDisplayed()
            rule.onNodeWithTag("search").assertIsDisplayed()
            assert(bounds("search").bottom <= root.bottom) { "search bar below the screen with $n cards at $scale" }
            assert(bounds("dock").bottom <= bounds("search").top) { "dock and search overlap with $n cards at $scale" }
            assert(bounds(FrameTags.Stack).bottom <= bounds("pins").top) { "stack runs under the pins with $n cards at $scale" }
            assert(bounds(FrameTags.Stack).top >= bounds("top").bottom) { "stack runs under the clock with $n cards at $scale" }
        }
    }

    @Test fun `the last card can be scrolled to and ends above the pins`() {
        settle { cards = 12 }
        rule.onNodeWithTag("card11").performScrollTo().assertIsDisplayed()
        assert(bounds("card11").bottom <= bounds("pins").top)
    }

    @Test fun `a swipe on a stack that fits opens the layers`() {
        settle { cards = 0 }
        val (x, y) = midStack()
        drag(x, y, -150f)
        assertEquals(1 to 0, swipesSeen())
        drag(x, y, 150f)
        assertEquals(1 to 1, swipesSeen())
    }

    @Test fun `a swipe up at the top of a long stack only scrolls it, a swipe down opens the shade`() {
        settle { cards = 12 }
        val (x, y) = midStack()
        drag(x, y, -150f)
        assertEquals(0 to 0, swipesSeen())
        settle { scrollStackBy(-100_000f) }
        drag(x, y, 150f)
        assertEquals(0 to 1, swipesSeen())
    }

    @Test fun `in the middle of a long stack swipes only scroll`() {
        settle { cards = 12; scrollStackBy(1500f) }
        val (x, y) = midStack()
        drag(x, y, -100f)
        drag(x, y, 100f)
        assertEquals(0 to 0, swipesSeen())
    }

    @Test fun `a swipe up at the end of a long stack opens the drawer once`() {
        settle { cards = 12; scrollStackBy(100_000f) }
        val (x, y) = midStack()
        drag(x, y, -150f)
        assertEquals(1 to 0, swipesSeen())
        drag(x, y, 150f)
        assertEquals(1 to 0, swipesSeen())
    }

    @Test fun `a swipe that starts outside the stack acts wherever the stack is scrolled`() {
        settle { cards = 12; scrollStackBy(1500f) }
        val top = bounds("top")
        drag(206f, (top.top.value + top.bottom.value) / 2, 150f)
        assertEquals(0 to 1, swipesSeen())
        val search = bounds("search")
        drag(206f, (search.top.value + search.bottom.value) / 2, -150f)
        assertEquals(1 to 1, swipesSeen())
    }

    @Test fun `a short drag is not a swipe`() {
        settle { cards = 0 }
        val (x, y) = midStack()
        drag(x, y, -20f)
        assertEquals(0 to 0, swipesSeen())
    }

    @Test fun `a swipe from the 24 dp side edges is left to Android's back gesture`() {
        settle { cards = 0 }
        val (_, y) = midStack()
        drag(10f, y, -150f)
        drag(412f - 10f, y, 150f)
        assertEquals(0 to 0, swipesSeen())
    }

    @Test fun `no gestures start on a widget`() {
        settle { cards = 0; zone = true }
        val z = bounds("zone")
        val y = (z.top.value + z.bottom.value) / 2
        drag(206f, y, 100f)
        rule.onRoot().performTouchInput { longClick(Offset(206.dp.toPx(), y.dp.toPx())) }
        assertEquals(0 to 0, swipesSeen())
        assertEquals(0, long)
    }

    @Test fun `nothing fires while gestures are off`() {
        settle { cards = 0; gestures = false }
        val (x, y) = midStack()
        drag(x, y, -150f)
        drag(x, y, 150f)
        rule.onRoot().performTouchInput { longClick(Offset(x.dp.toPx(), y.dp.toPx())) }
        assertEquals(0 to 0, swipesSeen())
        assertEquals(0, long)
    }

    @Test fun `a long press on a card opens edit mode once and is not also a swipe`() {
        settle { cards = 3 }
        val b = bounds("card1")
        rule.onRoot().performTouchInput { longClick(Offset(206.dp.toPx(), ((b.top.value + b.bottom.value) / 2).dp.toPx())) }
        assertEquals(1, long)
        assertEquals(0 to 0, swipesSeen())
    }

    private fun twoFingers(x: Float, yDp: Float, dx0: Float, dy0: Float, dx1: Float, dy1: Float) = rule.onRoot().performTouchInput {
        val a = Offset((x - 40f).dp.toPx(), yDp.dp.toPx())
        val b = Offset((x + 40f).dp.toPx(), yDp.dp.toPx())
        down(0, a); down(1, b)
        val steps = 10
        for (i in 1..steps) {
            advanceEventTime(80)
            updatePointerTo(0, Offset(a.x + dx0.dp.toPx() * i / steps, a.y + dy0.dp.toPx() * i / steps))
            updatePointerTo(1, Offset(b.x + dx1.dp.toPx() * i / steps, b.y + dy1.dp.toPx() * i / steps))
            move()
        }
        up(0); up(1)
    }

    private fun tapsSeen() = listOf(twoUp, twoDown, pinch, double)

    @Test fun `two fingers down and up fire their own callbacks once and no one-finger swipe`() {
        settle { cards = 0 }
        val (x, y) = midStack()
        twoFingers(x, y, 0f, 150f, 0f, 150f)
        assertEquals(listOf(0, 1, 0, 0), tapsSeen())
        twoFingers(x, y, 0f, -150f, 0f, -150f)
        assertEquals(listOf(1, 1, 0, 0), tapsSeen())
        assertEquals(0 to 0, swipesSeen())
        assertEquals(0, long)
    }

    @Test fun `two fingers work on a stack that is scrolled to the middle`() {
        settle { cards = 12; scrollStackBy(1500f) }
        val (x, y) = midStack()
        twoFingers(x, y, 0f, 150f, 0f, 150f)
        assertEquals(listOf(0, 1, 0, 0), tapsSeen())
        assertEquals(0 to 0, swipesSeen())
    }

    @Test fun `a pinch in fires once and opens no layer`() {
        settle { cards = 0 }
        val (x, y) = midStack()
        twoFingers(x, y, 30f, 0f, -30f, 0f)
        assertEquals(listOf(0, 0, 1, 0), tapsSeen())
        assertEquals(0 to 0, swipesSeen())
    }

    @Test fun `spreading two fingers fires nothing`() {
        settle { cards = 0 }
        val (x, y) = midStack()
        twoFingers(x, y, -40f, 0f, 40f, 0f)
        assertEquals(listOf(0, 0, 0, 0), tapsSeen())
    }

    @Test fun `a double tap on empty space fires once, a single tap and a long press do not`() {
        settle { cards = 0 }
        val top = bounds("top")
        val midY = (top.top.value + top.bottom.value) / 2
        rule.onRoot().performTouchInput { click(Offset(206.dp.toPx(), midY.dp.toPx())) }
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals(0, double)
        rule.onRoot().performTouchInput { doubleClick(Offset(206.dp.toPx(), midY.dp.toPx())) }
        assertEquals(1, double)
        assertEquals(0, long)
        assertEquals(0 to 0, swipesSeen())
    }

    @Test fun `a double tap in the gaps of the stack fires too`() {
        settle { cards = 0 }
        val (x, y) = midStack()
        rule.onRoot().performTouchInput { doubleClick(Offset(x.dp.toPx(), y.dp.toPx())) }
        assertEquals(1, double)
    }

    @Test fun `the new gestures stay out of the side edges, widgets and edit mode`() {
        settle { cards = 0; zone = true }
        val (_, y) = midStack()
        rule.onRoot().performTouchInput { doubleClick(Offset(10.dp.toPx(), y.dp.toPx())) }
        twoFingers(10f + 40f, y, 0f, 150f, 0f, 150f) // one finger starts at x = 10
        val z = bounds("zone")
        val zy = (z.top.value + z.bottom.value) / 2
        rule.onRoot().performTouchInput { doubleClick(Offset(206.dp.toPx(), zy.dp.toPx())) }
        twoFingers(206f, zy, 0f, 150f, 0f, 150f)
        twoFingers(206f, zy, 30f, 0f, -30f, 0f)
        assertEquals(listOf(0, 0, 0, 0), tapsSeen())
        settle { gestures = false }
        val (x, my) = midStack()
        rule.onRoot().performTouchInput { doubleClick(Offset(x.dp.toPx(), my.dp.toPx())) }
        twoFingers(x, my, 0f, 150f, 0f, 150f)
        twoFingers(x, my, 30f, 0f, -30f, 0f)
        assertEquals(listOf(0, 0, 0, 0), tapsSeen())
    }

    @Test fun `a long press on empty space outside the stack opens edit mode once`() {
        settle { cards = 3 }
        val top = bounds("top")
        rule.onRoot().performTouchInput { longClick(Offset(206.dp.toPx(), ((top.top.value + top.bottom.value) / 2).dp.toPx())) }
        assertEquals(1, long)
    }
}
