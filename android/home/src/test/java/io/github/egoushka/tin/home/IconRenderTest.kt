package io.github.egoushka.tin.home

import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import de.mm20.launcher2.icons.ColorLayer
import de.mm20.launcher2.icons.StaticIconLayer
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.icons.TintedIconLayer
import de.mm20.launcher2.icons.TransparentLayer
import io.github.egoushka.tin.home.apps.onTile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B15 to B17: the drawer's icon drawing, and Tin Home's own icon (F8). The icons are built the way the engine's
 * LauncherApp.loadIcon builds them from an adaptive icon (layers at 1.5x, the monochrome one tinted on a theme plate).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h914dp-420dpi")
class IconRenderTest {
    private val px = 96
    private val tint = Color.WHITE
    private val background = ColorDrawable(Color.BLUE)
    private val foreground = ColorDrawable(Color.RED)

    @Test fun `a monochrome layer is drawn in the tint`() = runBlocking {
        val icon = StaticLauncherIcon(TintedIconLayer(ColorDrawable(Color.BLACK), scale = 1.5f), ColorLayer())
        val bmp = icon.onTile(px, tint)
        assertEquals(tint, bmp.getPixel(px / 2, px / 2))
    }

    @Test fun `with no monochrome layer the icon keeps its own colours inside the disc`() = runBlocking {
        val icon = StaticLauncherIcon(StaticIconLayer(foreground, scale = 1.5f), StaticIconLayer(background, scale = 1.5f))
        val bmp = icon.onTile(px, tint)
        assertEquals(Color.RED, bmp.getPixel(px / 2, px / 2))
        assertEquals(Color.TRANSPARENT, bmp.getPixel(1, 1))
    }

    @Test fun `a legacy icon keeps its own shape`() = runBlocking {
        val bmp = StaticLauncherIcon(StaticIconLayer(foreground), TransparentLayer).onTile(px, tint)
        assertEquals(Color.RED, bmp.getPixel(1, 1))
    }

    @Test fun `Tin Home ships an adaptive icon with a monochrome layer`() {
        val context = RuntimeEnvironment.getApplication()
        val id = context.applicationInfo.icon
        assertTrue("the manifest declares no icon", id != 0)
        val icon = context.getDrawable(id)
        assertTrue("launcher icon is ${icon?.javaClass?.simpleName}", icon is AdaptiveIconDrawable)
        assertNotNull((icon as AdaptiveIconDrawable).monochrome)
    }
}
