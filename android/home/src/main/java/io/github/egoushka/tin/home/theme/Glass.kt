package io.github.egoushka.tin.home.theme

import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect as PlatformRenderEffect
import android.graphics.Shader
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.egoushka.tin.home.tune.Tune
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

private fun DrawScope.glowEllipse(color: Color, alpha: Float, center: Offset, rx: Float, ry: Float, fade: Float) {
    if (alpha <= 0f || rx <= 0f || ry <= 0f) return
    scale(scaleX = 1f, scaleY = ry / rx, pivot = center) {
        drawRect(
            Brush.radialGradient(0f to color.copy(alpha = alpha), fade to Color.Transparent, center = center, radius = rx),
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

/** `blur(frost) saturate(160%)` as one RenderEffect, so the saturation is baked into the shared bitmap once. */
private fun frostEffect(radius: Float): RenderEffect {
    val saturate = ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(1.6f) })
    val blur = PlatformRenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
    return PlatformRenderEffect.createColorFilterEffect(saturate, blur).asComposeRenderEffect()
}

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
        layer.renderEffect = if (tune.usesFrost) frostEffect(radius) else null
        layer.record(density, direction, size) { drawRoom(tune.roomLight, screen) }
        frost.screen = screen
        frost.bitmap = layer.toImageBitmap()
    }
}

/**
 * Night glass: the frosted room behind, `surface` at the tint knob's alpha, a 1 dp edge lit from above. [light] is
 * the attention ladder (glow-1 needs you, bad broken); null is calm and carries no colour.
 */
@Composable
fun Modifier.glass(shape: Shape, light: Color? = null): Modifier {
    val frost = LocalFrost.current
    val tune = LocalTune.current
    val edgeTop = if (light != null) lerpColor(Ng.edgeHi, light, 0.6f) else Ng.edgeHi
    val edge = if (light != null) lerpColor(Ng.edge, light, 0.55f) else Ng.edge
    return this
        .clip(shape)
        .then(GlassElement(light, frost, tune))
        .border(1.dp, Brush.verticalGradient(0f to edgeTop, 0.1f to edge, 1f to edge), shape)
}

private data class GlassElement(val light: Color?, val frost: Frost, val tune: Tune) : ModifierNodeElement<GlassNode>() {
    override fun create() = GlassNode(light, frost, tune)

    override fun update(node: GlassNode) {
        node.light = light
        node.frost = frost
        node.tune = tune
        node.invalidateDraw()
    }
}

/** Draws the card's crop of the shared frost bitmap; the position is a plain field, so tracking it costs no state. */
private class GlassNode(var light: Color?, var frost: Frost, var tune: Tune) :
    Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var pos = Offset.Zero

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val p = coordinates.positionInRoot()
        if (p == pos) return
        pos = p
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        val b = frost.bitmap
        if (b != null) {
            val src = IntOffset(pos.x.roundToInt().coerceAtLeast(0), pos.y.roundToInt().coerceAtLeast(0))
            val w = size.width.roundToInt().coerceAtMost(b.width - src.x).coerceAtLeast(0)
            val h = size.height.roundToInt().coerceAtMost(b.height - src.y).coerceAtLeast(0)
            drawImage(b, srcOffset = src, srcSize = IntSize(w, h), dstSize = IntSize(w, h))
        } else {
            translate(-pos.x, -pos.y) { drawRoom(tune.roomLight, frost.screen) }
        }
        drawRect(Ng.surface.copy(alpha = tune.glassTint))
        light?.let {
            drawRect(Brush.radialGradient(0f to it.copy(alpha = 0.3f), 0.7f to Color.Transparent,
                center = Offset.Zero, radius = size.width * 1.2f))
        }
        drawContent()
    }
}

fun lerpColor(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t,
    a.blue + (b.blue - a.blue) * t, a.alpha + (b.alpha - a.alpha) * t,
)
