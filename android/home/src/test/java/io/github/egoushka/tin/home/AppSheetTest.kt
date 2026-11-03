package io.github.egoushka.tin.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.egoushka.tin.home.apps.AppEntry
import io.github.egoushka.tin.home.apps.AppPrefs
import io.github.egoushka.tin.home.apps.AppRepository
import io.github.egoushka.tin.home.layout.PinStore
import io.github.egoushka.tin.home.pages.PageActions
import io.github.egoushka.tin.home.pages.PagePlace
import io.github.egoushka.tin.home.ui.AppSheet
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** B3: the long-press menu composes in every branch; with a missing icon it used to crash on the first press (F1). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h914dp-420dpi")
class AppSheetTest {
    @get:Rule val rule = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()
    private val app = AppEntry("Signal", "org.signal", "org.signal.Main", 0)
    private var closed = 0
    private var hidden = 0

    private fun pages(place: PagePlace?, count: Int = 2) = PageActions(
        count = count, place = place, canEarlier = place != null, canLater = place != null,
        addApp = {}, addShortcut = { _, _ -> }, move = {}, step = {}, remove = {},
    )

    private fun show(dockSlot: Int?, pins: List<String>, pages: PageActions?) = rule.setContent {
        AppSheet(
            app, AppRepository(context, AppPrefs(context)), MutableStateFlow(null), dockSlot, pins,
            onPin = {}, onReplace = {}, onHide = { hidden++ }, onClose = { closed++ }, pages = pages,
        )
    }

    private fun assertRows() {
        rule.onNodeWithText(context.getString(R.string.app_info)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.app_hide)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.app_uninstall)).assertIsDisplayed()
    }

    private val full = List(PinStore.MAX) { "pkg$it/Main#0" }

    @Test fun `a drawer app that is not pinned offers dock and Home`() {
        show(null, emptyList(), null)
        assertRows()
        rule.onNodeWithText(context.getString(R.string.app_pin)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.app_pin_home)).assertIsDisplayed()
    }

    @Test fun `a pinned app offers to unpin it from Home`() {
        show(null, listOf(app.key), null)
        assertRows()
        rule.onNodeWithText(context.getString(R.string.app_unpin_home)).assertIsDisplayed()
    }

    @Test fun `with every Home pin used an unpinned app offers neither`() {
        show(null, full, null)
        assertRows()
        assertEquals(0, rule.onAllNodes(hasText(context.getString(R.string.app_pin_home))).fetchSemanticsNodes().size)
    }

    @Test fun `a docked app offers to replace`() {
        show(2, emptyList(), null)
        assertRows()
        rule.onNodeWithText(context.getString(R.string.app_replace)).assertIsDisplayed()
    }

    @Test fun `choosing a dock slot lists the five slots`() {
        show(null, emptyList(), null)
        rule.onNodeWithText(context.getString(R.string.app_pin)).performClick()
        rule.onNodeWithText(context.getString(R.string.dock_choose_slot).uppercase()).assertIsDisplayed()
    }

    @Test fun `page rows compose for an app on no page and for one on a page`() {
        show(null, emptyList(), pages(null))
        assertRows()
        rule.onNodeWithText(context.getString(R.string.page_add_to)).assertIsDisplayed()
    }

    @Test fun `page rows for an app already on a page`() {
        show(null, emptyList(), pages(PagePlace(1, "id")))
        assertRows()
        rule.onNodeWithText(context.getString(R.string.page_item_remove)).assertIsDisplayed()
    }

    @Test fun `hide asks the screen to hide`() {
        show(null, emptyList(), null)
        rule.onNodeWithText(context.getString(R.string.app_hide)).performClick()
        assertEquals(1, hidden)
    }
}
