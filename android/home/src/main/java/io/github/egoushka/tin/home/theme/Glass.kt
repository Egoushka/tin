package io.github.egoushka.tin.home.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.egoushka.tin.home.tune.Tune
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The dark room (bundle.css `--paint` for Night glass): bg, glow-3 from the top left and a faint glow-2 at the bottom
 * right, both scaled by the room-light knob. [screen] is the whole window, so a card can draw its own part of it.
 */
fun DrawScope.drawRoom(roomLight: Float, screen: Size) {
    drawRect(Ng.bg)
    glowEllipse(Ng.glow3, (roomLight * 0.85f).coerceAtMost(1f), Offset.Zero, 0.85f * screen.width, 0.45f * screen.height, 0.75f)
    glowEllipse(Ng.glow2, (roomLight * 0.22f).coerceAtMost(1f), Offset(screen.width, screen.height),
        0.70f * screen.width, 0.38f * screen.height, 0.72f)
}

/** A CSS radial-gradient from [color] at [alpha] to the same colour at 0, [rx] by [ry] wide, fully faded at [fade]. */
private fun DrawScope.glowEllipse(color: Color, alpha: Float, center: Offset, rx: Float, ry: Float, fade: Float) {
    if (alpha <= 0f || rx <= 0f || ry <= 0f) return
    scale(scaleX = 1f, scaleY = ry / rx, pivot = center) {
        drawRect(
            // CSS fades premultiplied; Compose's Transparent is black, which would darken the middle of the fade.
            Brush.radialGradient(0f to color.copy(alpha = alpha), fade to color.copy(alpha = 0f), center = center, radius = rx),
            topLeft = Offset(center.x - rx, center.y - rx), size = Size(2 * rx, 2 * rx),
        )
    }
}

/** The frosted room, rendered once per knob change and shared by every glass part. */
@Stable
class Frost {
    var bitmap by mutableStateOf<ImageBitmap?>(null)
    var screen by mutableStateOf(Size.Zero)
}

val LocalFrost = staticCompositionLocalOf { Frost() }
val LocalTune = staticCompositionLocalOf { Tune() }

private val saturate = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.6f) })

/** Renders the room through `blur(frost) saturate(160%)` into [frost]. Frost 0 records no RenderEffect at all. */
@Composable
fun FrostRenderer(tune: Tune, size: IntSize, frost: Frost) {
    val layer = rememberGraphicsLayer()
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    LaunchedEffect(tune.roomLight, tune.frostDp, size) {
        if (size.width == 0 || size.height == 0) return@LaunchedEffect
        val screen = Size(size.width.toFloat(), size.height.toFloat())
        val radius = with(density) { tune.frostDp.dp.toPx() }
        layer.renderEffect = if (tune.usesFrost) BlurEffect(radius, radius, TileMode.Clamp) else null
        layer.record(density, direction, size) { drawRoom(tune.roomLight, screen) }
        frost.screen = screen
        frost.bitmap = layer.toImageBitmap()
    }
}

/**
 * The Night glass numbers of bundle.css, one to one (NightGlassCssTest parses the CSS and compares). Lengths are CSS px,
 * which are dp on the 412 dp phone the design is drawn at; alphas and mixes are fractions.
 */
object GlassPaint {
    // --w-shadow: inset 0 1px 0 edge-hi, 0 16px 32px -16px rgb(0 0 0 / .6)
    const val EDGE_LIT_DY = 1f
    const val DROP_DY = 16f
    const val DROP_BLUR = 32f
    const val DROP_SPREAD = -16f
    const val DROP_ALPHA = 0.6f

    // .w.hot: radial-gradient(120% 90% at 0% 0%, glow-1 30%, transparent 70%); border glow-1 55% over edge;
    // inset line glow-1 60% over edge-hi; halo 0 0 36px -12px glow-1
    const val NEEDS_RX = 1.2f
    const val NEEDS_RY = 0.9f
    const val NEEDS_CX = 0f
    const val NEEDS_CY = 0f
    const val NEEDS_ALPHA = 0.30f
    const val NEEDS_FADE = 0.70f
    const val NEEDS_BORDER = 0.55f
    const val NEEDS_LINE = 0.60f
    const val HALO_BLUR = 36f
    const val HALO_SPREAD = -12f

    // .w.mini.bad: radial-gradient(110% 100% at 0% 50%, bad 22%, transparent 70%); border bad 45% over edge
    const val BROKEN_RX = 1.1f
    const val BROKEN_RY = 1.0f
    const val BROKEN_CX = 0f
    const val BROKEN_CY = 0.5f
    const val BROKEN_ALPHA = 0.22f
    const val BROKEN_FADE = 0.70f
    const val BROKEN_BORDER = 0.45f

    // .ag-row.now: signal 10% fill, inset 0 0 0 1px signal 22%
    const val NOW_FILL = 0.10f
    const val NOW_RING = 0.22f
    const val NOW_RING_WIDTH = 1f

    const val BORDER_WIDTH = 1f
}

/** CSS `color-mix(in srgb, a p%, b)`: premultiplied, so a transparent [b] keeps [a]'s hue and scales its alpha. */
fun colorMix(a: Color, p: Float, b: Color): Color {
    val alpha = p * a.alpha + (1f - p) * b.alpha
    if (alpha <= 0f) return Color.Transparent
    fun channel(x: Float, y: Float) = (p * a.alpha * x + (1f - p) * b.alpha * y) / alpha
    return Color(channel(a.red, b.red), channel(a.green, b.green), channel(a.blue, b.blue), alpha)
}

/** Linear blend of two colours, premultiplied like CSS: `lerpColor(a, b, t)` is `colorMix(b, t, a)`. */
fun lerpColor(a: Color, b: Color, t: Float) = colorMix(b, t, a)

/**
 * Night glass without a card's light: the frosted room behind, `surface` at the tint knob's alpha, a 1 dp edge and the
 * 1 dp line of light along the top. Dock tiles, the search bar and sheets use this.
 */
fun Modifier.glass(shape: Shape): Modifier = glassBody(shape)

/**
 * The card body. [needsYou] and [broken] (0..1) are read while drawing, so a light animating never recomposes; they
 * paint the attention colour or red over the glass and tint the edge. Both 0 is calm. [pressed] (0..1) is the dock
 * tile's pressed look.
 */
fun Modifier.glassBody(
    shape: Shape,
    attention: Color = Ng.glow1,
    needsYou: () -> Float = { 0f },
    broken: () -> Float = { 0f },
    pressed: () -> Float = { 0f },
): Modifier = composed {
    val frost = LocalFrost.current
    val tune = LocalTune.current
    var pos by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { pos = it.positionInRoot() }
        .drawWithCache {
            val outer = shape.round(size, layoutDirection, this)
            val inner = outer.inset(GlassPaint.BORDER_WIDTH.dp.toPx())
            val outerPath = Path().apply { addRoundRect(outer) }
            val innerPath = Path().apply { addRoundRect(inner) }
            val ring = Path.combine(PathOperation.Difference, outerPath, innerPath)
            val shifted = Path().apply {
                addRoundRect(inner)
                translate(Offset(0f, GlassPaint.EDGE_LIT_DY.dp.toPx()))
            }
            val crescent = Path.combine(PathOperation.Difference, innerPath, shifted)
            onDrawBehind {
                val n = needsYou()
                val b = broken()
                val p = pressed()
                clipPath(outerPath) {
                    drawFrost(frost, tune, pos)
                    // `.ic:active i`: the tint gives way to surface-2 and the border to edge-hi.
                    drawRect(lerpColor(Ng.surface.copy(alpha = tune.glassTint), Ng.surface2, p))
                    if (n > 0f) drawLight(attention, GlassPaint.NEEDS_ALPHA * n, GlassPaint.NEEDS_FADE,
                        GlassPaint.NEEDS_CX, GlassPaint.NEEDS_CY, GlassPaint.NEEDS_RX, GlassPaint.NEEDS_RY)
                    if (b > 0f) drawLight(Ng.bad, GlassPaint.BROKEN_ALPHA * b, GlassPaint.BROKEN_FADE,
                        GlassPaint.BROKEN_CX, GlassPaint.BROKEN_CY, GlassPaint.BROKEN_RX, GlassPaint.BROKEN_RY)
                }
                var edge = Ng.edge
                var line = Ng.edgeHi
                if (n > 0f) {
                    edge = lerpColor(edge, colorMix(attention, GlassPaint.NEEDS_BORDER, Ng.edge), n)
                    line = lerpColor(line, colorMix(attention, GlassPaint.NEEDS_LINE, Ng.edgeHi), n)
                }
                if (b > 0f) edge = lerpColor(edge, colorMix(Ng.bad, GlassPaint.BROKEN_BORDER, Ng.edge), b)
                edge = lerpColor(edge, Ng.edgeHi, p)
                drawPath(ring, edge)
                drawPath(crescent, line)
            }
        }
}

private val RowShape = RoundedCornerShape(Dimen.radiusRow)

/** `.ag-row.now`: the signal fill and its 1 dp inner ring, arriving and leaving with the now row of the motion table. */
fun Modifier.nowLight(current: Boolean): Modifier = composed {
    val amount = animateFloatAsState(
        if (current) 1f else 0f, Motion.tween(LightMotion.alpha(LightKind.Now, current), rememberAnimatorScale()), label = "now",
    )
    drawWithCache {
        val outer = RowShape.round(size, layoutDirection, this)
        val ring = Path.combine(
            PathOperation.Difference,
            Path().apply { addRoundRect(outer) },
            Path().apply { addRoundRect(outer.inset(GlassPaint.NOW_RING_WIDTH.dp.toPx())) },
        )
        onDrawBehind {
            val a = amount.value
            if (a > 0f) {
                drawRoundRect(Ng.signal.copy(alpha = GlassPaint.NOW_FILL * a), cornerRadius = outer.topLeftCornerRadius)
                drawPath(ring, Ng.signal.copy(alpha = GlassPaint.NOW_RING * a))
            }
        }
    }
}

private fun DrawScope.drawFrost(frost: Frost, tune: Tune, pos: Offset) {
    val b = frost.bitmap
    if (b != null) {
        val src = IntOffset(pos.x.roundToInt().coerceAtLeast(0), pos.y.roundToInt().coerceAtLeast(0))
        val w = size.width.roundToInt().coerceAtMost(b.width - src.x).coerceAtLeast(0)
        val h = size.height.roundToInt().coerceAtMost(b.height - src.y).coerceAtLeast(0)
        drawImage(b, srcOffset = src, srcSize = IntSize(w, h), dstSize = IntSize(w, h),
            colorFilter = if (tune.usesFrost) saturate else null)
    } else {
        translate(-pos.x, -pos.y) { drawRoom(tune.roomLight, frost.screen) }
    }
}

/** `radial-gradient(rx% ry% at cx cy, color alpha, transparent fade)` over the card; the radii are fractions of its size. */
private fun DrawScope.drawLight(color: Color, alpha: Float, fade: Float, cx: Float, cy: Float, rx: Float, ry: Float) {
    val center = Offset(cx * size.width, cy * size.height)
    glowEllipse(color, alpha, center, rx * size.width, ry * size.height, fade)
}

/**
 * A CSS outer box-shadow: [color] at [alpha] (read while drawing), the card's shape moved down by [dy], grown by
 * [spread] (negative shrinks it), blurred by [blur] and cut out under the card, as CSS never paints it beneath the box.
 * Drawn by a modifier of its own so a parent's alpha layer cannot clip the part that falls outside the card.
 */
fun Modifier.softShadow(
    shape: Shape,
    color: Color,
    dy: Float,
    blur: Float,
    spread: Float,
    alpha: () -> Float,
): Modifier = drawWithCache {
    val layer = obtainGraphicsLayer()
    val sigma = (blur / 2f).dp.toPx()
    val bleed = (3f * sigma + abs(dy).dp.toPx() + abs(spread).dp.toPx()).roundToInt()
    val card = shape.round(size, layoutDirection, this)
    val grown = card.inset(-spread.dp.toPx())
    val cutOut = Path().apply { addRoundRect(card) }
    layer.renderEffect = BlurEffect(sigma, sigma, TileMode.Decal)
    layer.record(this, layoutDirection, IntSize(size.width.roundToInt() + 2 * bleed, size.height.roundToInt() + 2 * bleed)) {
        translate(bleed.toFloat(), bleed.toFloat()) {
            drawPath(Path().apply { addRoundRect(grown) }, color)
        }
    }
    onDrawWithContent {
        val a = alpha()
        if (a > 0f) {
            layer.alpha = a
            clipPath(cutOut, ClipOp.Difference) {
                translate(-bleed.toFloat(), -bleed.toFloat() + dy.dp.toPx()) { drawLayer(layer) }
            }
        }
        drawContent()
    }
}

/** The card's shape as a rounded rectangle; every Night glass shape is one. */
private fun Shape.round(size: Size, direction: LayoutDirection, density: Density): RoundRect =
    when (val outline = createOutline(size, direction, density)) {
        is Outline.Rounded -> outline.roundRect
        is Outline.Rectangle -> RoundRect(outline.rect)
        is Outline.Generic -> RoundRect(outline.path.getBounds())
    }

/** The rounded rectangle moved in by [d] on every side, with the corners kept concentric. */
private fun RoundRect.inset(d: Float): RoundRect {
    fun r(c: CornerRadius) = CornerRadius((c.x - d).coerceAtLeast(0f), (c.y - d).coerceAtLeast(0f))
    return RoundRect(left + d, top + d, right - d, bottom - d,
        r(topLeftCornerRadius), r(topRightCornerRadius), r(bottomRightCornerRadius), r(bottomLeftCornerRadius))
}
