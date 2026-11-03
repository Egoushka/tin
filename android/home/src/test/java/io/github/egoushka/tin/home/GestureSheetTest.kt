package io.github.egoushka.tin.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import io.github.egoushka.tin.home.gestures.Gesture
import io.github.egoushka.tin.home.gestures.GestureAction
import io.github.egoushka.tin.home.gestures.GestureMap
import io.github.egoushka.tin.home.tune.Tune
import io.github.egoushka.tin.home.ui.GestureSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The sheet where gestures 1 to 7 are reassigned: every row reads as "gesture, action" and a tap picks the next action. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h914dp-420dpi")
class GestureSheetTest {
    @get:Rule val rule = createComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()
    private var map by mutableStateOf(GestureMap())
    private var picks = emptyList<Pair<Gesture, GestureAction>>()
    private var closed = 0

    private fun show() = rule.setContent {
        GestureSheet(Tune(), map, onPick = { g, a -> picks = picks + (g to a); map = map.with(g, a) }, onClose = { closed++ })
    }

    private fun row(g: Gesture, a: GestureAction) =
        rule.onNode(hasContentDescription(app.getString(R.string.gesture_row, app.getString(g.label), app.getString(a.label))))

    @Test fun `every gesture is a row that says what it does now`() {
        show()
        for (g in Gesture.entries) row(g, GestureMap().of(g)).assertIsDisplayed().assertHasClickAction()
    }

    @Test fun `a tap picks the next action and the row then says so`() {
        show()
        row(Gesture.DoubleTap, GestureAction.Torch).performClick()
        val next = GestureAction.Torch.next()
        assertEquals(listOf(Gesture.DoubleTap to next), picks)
        row(Gesture.DoubleTap, next).assertIsDisplayed()
    }

    @Test fun `showing the sheet changes nothing`() {
        show()
        rule.waitForIdle()
        assertEquals(emptyList<Pair<Gesture, GestureAction>>(), picks)
        assertEquals(GestureMap(), map)
    }
}
