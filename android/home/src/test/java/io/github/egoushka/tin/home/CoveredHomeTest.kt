package io.github.egoushka.tin.home

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.egoushka.tin.home.apps.AppEntry
import io.github.egoushka.tin.home.apps.AppPrefs
import io.github.egoushka.tin.home.apps.AppRepository
import io.github.egoushka.tin.home.tune.Tune
import io.github.egoushka.tin.home.ui.Layer
import io.github.egoushka.tin.home.ui.coveredBy
import io.github.egoushka.tin.home.ui.DockTile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** B22 and B23: the real dock tile under the cover; the drawer above it stays reachable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h914dp-420dpi")
class CoveredHomeTest {
    @get:Rule val rule = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()
    private val signal = AppEntry("Signal", "org.signal", "org.signal.Main", 0)

    private fun show(layer: Layer) = rule.setContent {
        Box {
            Box(Modifier.coveredBy(layer)) {
                DockTile(Tune(), DockDefault.Phone, 0, signal, ready = true, AppRepository(context, AppPrefs(context)), Actions(context))
            }
            Text("Drawer")
        }
    }

    private fun dockTiles() = rule.onAllNodes(hasContentDescription("Signal")).fetchSemanticsNodes().size

    @Test fun `with Home as the layer the dock tile is in the tree`() {
        show(Layer.Home)
        assertEquals(1, dockTiles())
    }

    @Test fun `under the drawer the dock tile is not in the tree, and the drawer is`() {
        show(Layer.Apps)
        assertEquals(0, dockTiles())
        rule.onNode(hasText("Drawer")).assertExists()
    }

    @Test fun `under search the dock tile is not in the tree`() {
        show(Layer.Search)
        assertEquals(0, dockTiles())
    }

    @Test fun `under Tune the dock tile is not in the tree`() {
        show(Layer.Tune)
        assertEquals(0, dockTiles())
    }

    @Test fun `under Hidden apps the dock tile is not in the tree`() {
        show(Layer.Hidden)
        assertEquals(0, dockTiles())
    }
}
