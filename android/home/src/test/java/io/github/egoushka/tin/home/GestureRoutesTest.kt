package io.github.egoushka.tin.home

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.egoushka.tin.home.ui.GlanceRoutes
import io.github.egoushka.tin.home.ui.glanceActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Every gesture has a route that needs no gesture: the clock carries them as screen-reader actions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h914dp-420dpi")
class GestureRoutesTest {
    @get:Rule val rule = createComposeRule()

    private val app get() = RuntimeEnvironment.getApplication()
    private val ran = mutableListOf<String>()

    private fun show() = rule.setContent {
        val routes = GlanceRoutes(
            apps = { ran += "apps" }, notifications = { ran += "notifications" },
            quickSettings = { ran += "quickSettings" }, today = { ran += "today" }, tune = { ran += "tune" },
        )
        Box(Modifier.testTag("glance").glanceActions(routes)) { Text("12:00") }
    }

    private fun actions() = rule.onNodeWithTag("glance").fetchSemanticsNode().config
        .getOrNull(SemanticsActions.CustomActions).orEmpty()

    @Test fun `the clock names an action for each route`() {
        show()
        val labels = actions().map { it.label }
        assertEquals(
            listOf(R.string.action_apps, R.string.action_notifications, R.string.action_quick_settings, R.string.action_today, R.string.tune)
                .map(app::getString),
            labels,
        )
    }

    @Test fun `each action runs its own route`() {
        show()
        rule.runOnIdle { actions().forEach { it.action() } }
        assertEquals(listOf("apps", "notifications", "quickSettings", "today", "tune"), ran)
    }
}
