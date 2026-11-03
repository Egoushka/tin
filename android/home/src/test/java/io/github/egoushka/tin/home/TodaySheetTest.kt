package io.github.egoushka.tin.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.egoushka.tin.home.signals.Agenda
import io.github.egoushka.tin.home.signals.Event
import io.github.egoushka.tin.home.signals.NeedsItem
import io.github.egoushka.tin.home.signals.NeedsYou
import io.github.egoushka.tin.home.tune.Tune
import io.github.egoushka.tin.home.ui.TodaySheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The Today sheet is the card without its cap of three: every event still to come, then the approvals queue. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h914dp-420dpi")
class TodaySheetTest {
    @get:Rule val rule = createComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()
    private val now = 10 * 3_600_000L
    private val hour = 3_600_000L

    private fun events(n: Int) = List(n) { Event(it.toLong(), "Event $it", now + (it + 1) * hour, now + (it + 1) * hour + hour / 2, null) }

    private var approvals = 0

    private fun show(n: Int, needs: NeedsYou = NeedsYou.Empty) = rule.setContent {
        TodaySheet(Tune(), Agenda.all(events(n), now), needs, onApprovals = { approvals++ }, onClose = {})
    }

    @Test fun `every event still to come is listed, not three`() {
        show(6)
        for (i in 0 until 6) rule.onNodeWithText("Event $i").assertIsDisplayed()
    }

    @Test fun `an empty day says so`() {
        show(0)
        rule.onNodeWithText(app.getString(R.string.today_empty)).assertIsDisplayed()
    }

    @Test fun `the queue follows the events when something waits`() {
        val item = NeedsItem("1", "Merge the thing", "claude", 1L, false, "approvals://1")
        show(2, NeedsYou.of(listOf(item), open = 1, held = 0))
        rule.onNodeWithText(app.getString(R.string.today_queue)).assertIsDisplayed()
        rule.onNodeWithText("Merge the thing").assertIsDisplayed()
    }

    @Test fun `no queue label while nothing waits`() {
        show(2)
        assertEquals(0, rule.onAllNodes(hasText(app.getString(R.string.today_queue))).fetchSemanticsNodes().size)
    }

    @Test fun `a way into the approvals app is there`() {
        show(1)
        rule.onNodeWithText(app.getString(R.string.today_open_approvals)).performClick()
        assertEquals(1, approvals)
    }
}
