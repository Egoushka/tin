package io.github.egoushka.tin.home

import kotlin.math.abs
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.egoushka.tin.home.apps.AppPrefs
import io.github.egoushka.tin.home.apps.AppRepository
import io.github.egoushka.tin.home.layout.AppPicker
import io.github.egoushka.tin.home.tune.Tune
import io.github.egoushka.tin.home.ui.GestureSheet
import io.github.egoushka.tin.home.ui.HiddenSheet
import io.github.egoushka.tin.home.ui.TuneSheet
import io.github.egoushka.tin.home.gestures.GestureMap
import io.github.egoushka.tin.home.theme.Ng
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** B13, B14: a sheet's panel is solid, so nothing behind it shows through (F7), and Tune clears the status bar. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h914dp-420dpi")
class SheetOpaqueTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context get() = RuntimeEnvironment.getApplication()

    private fun onWhite(sheet: @Composable () -> Unit) = rule.setContent {
        Box(Modifier.fillMaxSize().background(Color.White)) { sheet() }
    }

    /**
     * A pixel in the panel's top padding, over no text. [merged]: the title node is the whole panel (the sheet's panel is
     * clickable, so its texts merge into one node); otherwise it is the title text and the padding is just above it.
     */
    private fun assertPanelSolid(title: String, merged: Boolean = true) {
        val node = rule.onAllNodes(hasText(title, substring = true, ignoreCase = true)).onFirst()
        val bounds = node.getBoundsInRoot()
        val image = rule.onRoot().captureToImage()
        val density = context.resources.displayMetrics.density
        val xDp = if (merged) (bounds.left.value + bounds.right.value) / 2 else bounds.left.value + 20
        val yDp = bounds.top.value + if (merged) 4 else -2
        val x = (xDp * density).toInt()
        val y = (yDp * density).toInt()
        val c = image.toPixelMap()[x, y]
        val surface = Ng.surface
        val off = maxOf(abs(c.red - surface.red), abs(c.green - surface.green), abs(c.blue - surface.blue))
        assertTrue("$title panel is not the solid surface: ${Integer.toHexString(c.toArgb())}", off < 0.02f)
    }

    @Test fun `the Tune panel is opaque`() {
        onWhite { TuneSheet(Tune(), {}, {}, 0, {}, {}) }
        assertPanelSolid(context.getString(R.string.tune), merged = false)
    }

    @Test fun `the Gestures panel is opaque`() {
        onWhite { GestureSheet(Tune(), GestureMap(), { _, _ -> }, {}) }
        assertPanelSolid(context.getString(R.string.gestures))
    }

    @Test fun `the Hidden apps panel is opaque`() {
        onWhite { HiddenSheet(AppRepository(context, AppPrefs(context)), {}, {}) }
        assertPanelSolid(context.getString(R.string.tune_hidden))
    }

    @Test fun `the picker panel is opaque`() {
        onWhite { AppPicker(AppRepository(context, AppPrefs(context)), emptyList(), {}, {}) }
        assertPanelSolid(context.getString(R.string.edit_add_app))
    }

    // Short enough that the panel fills the screen, so its top is the inset and not the room above it.
    @Config(qualifiers = "w412dp-h320dp-420dpi")
    @Test fun `the Tune panel starts below the status bar`() {
        onWhite { TuneSheet(Tune(), {}, {}, 0, {}, {}) }
        rule.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 48 * 3, 0, 0))
                .build()
            ViewCompat.dispatchApplyWindowInsets(rule.activity.window.decorView, insets)
        }
        rule.waitForIdle()
        val panelTop = rule.onAllNodes(hasText(context.getString(R.string.tune), substring = true, ignoreCase = true)).onFirst().getBoundsInRoot().top
        assertTrue("panel top $panelTop is under the 48 dp status bar", panelTop >= 48.dp)
    }
}
