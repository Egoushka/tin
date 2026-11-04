package io.github.egoushka.tin.home.apps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import de.mm20.launcher2.icons.DynamicLauncherIcon
import de.mm20.launcher2.icons.IconService
import de.mm20.launcher2.icons.LauncherIconRenderSettings
import de.mm20.launcher2.icons.StaticIconLayer
import de.mm20.launcher2.icons.StaticLauncherIcon
import de.mm20.launcher2.icons.TintedIconLayer
import de.mm20.launcher2.icons.TransparentLayer
import de.mm20.launcher2.search.Application
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** [app]'s icon from the engine as a [px] tile shows it ([onTile]); a clock or calendar icon as it is now. */
internal suspend fun IconService.tileIcon(app: Application, px: Int, tint: Int): Bitmap {
    // The service answers null until its providers are built.
    val icon = when (val found = getIcon(app, px).filterNotNull().first()) {
        is StaticLauncherIcon -> found
        is DynamicLauncherIcon -> found.getIcon(System.currentTimeMillis())
    }
    return icon.onTile(px, tint)
}

/**
 * The icon on a [px] tile: the themed monochrome layer in [tint] with no plate of its own, else the app's own colours
 * clipped to the disc the tile's glass is, since an adaptive icon's own mask is not the tile's. A legacy icon keeps
 * its own shape.
 */
internal suspend fun StaticLauncherIcon.onTile(px: Int, tint: Int): Bitmap {
    val themed = foregroundLayer is TintedIconLayer
    val legacy = backgroundLayer is TransparentLayer && (foregroundLayer as? StaticIconLayer)?.scale == 1f
    // Tones only reach layers with a colour of their own (placeholders); Home's glass is dark.
    val drawn = render(LauncherIconRenderSettings(px, tint, Color.TRANSPARENT, fgTone = 90, bgTone = 30))

    // The engine reuses its bitmap for the next render of this icon, so the tile gets a copy.
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    if (!themed && !legacy) canvas.clipPath(Path().apply { addCircle(px / 2f, px / 2f, px / 2f, Path.Direction.CW) })
    canvas.drawBitmap(drawn, 0f, 0f, null)
    return bmp
}
