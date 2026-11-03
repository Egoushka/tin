package io.github.egoushka.tin.home.apps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable

/**
 * An app's icon on a [px] tile: the themed monochrome layer in [tint], else the app's own colours. An adaptive icon
 * with no monochrome layer is clipped to the disc the tile's glass is, since its own mask is not the tile's.
 */
internal fun renderIcon(drawable: Drawable, px: Int, tint: Int): Bitmap {
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val adaptive = drawable as? AdaptiveIconDrawable
    // The adaptive foreground spans 108 dp around a 72 dp safe zone; draw it so the zone fills the tile.
    val extra = (px * 0.25f).toInt()
    val mono = adaptive?.monochrome
    when {
        mono != null -> {
            mono.setBounds(-extra, -extra, px + extra, px + extra)
            mono.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
            mono.draw(canvas)
        }
        adaptive != null -> {
            canvas.clipPath(Path().apply { addCircle(px / 2f, px / 2f, px / 2f, Path.Direction.CW) })
            adaptive.setBounds(-extra, -extra, px + extra, px + extra)
            adaptive.draw(canvas)
        }
        else -> {
            drawable.setBounds(0, 0, px, px)
            drawable.draw(canvas)
        }
    }
    return bmp
}
