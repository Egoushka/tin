package io.github.egoushka.tin.home.theme

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Material 3 motion tokens and easings, in one place; the transitions below are the table of cards/Light.md. */
object Motion {
    const val SHORT2 = 100
    const val SHORT4 = 200
    const val MEDIUM2 = 300
    const val LONG2 = 500
    const val EXTRA_LONG4 = 1000

    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    const val NEEDS_YOU_IN = MEDIUM2
    const val NEEDS_YOU_OUT = LONG2
    const val NOW_IN = MEDIUM2
    const val BROKEN_IN = SHORT4
    const val STALE_IN = EXTRA_LONG4
    const val FRESH_IN = SHORT2
    const val STALE_ALPHA = 0.55f

    /** Focus dim on calm cards (`.ph.focus .w`: opacity .4 over 220 ms, `--spring`). */
    const val FOCUS_DIM = 0.4f
    const val FOCUS_MS = 220

    /** Press feedback (`.ic:active i`: scale .92 over 160 ms, `--spring`). */
    const val PRESS_SCALE = 0.92f
    const val PRESS_MS = 160

    /** Reduced motion (animator scale 0) makes every transition instant. */
    fun duration(ms: Int, animatorScale: Float): Int = if (animatorScale == 0f) 0 else ms

    fun tween(spec: MotionSpec, animatorScale: Float): TweenSpec<Float> =
        tween(duration(spec.ms, animatorScale), easing = spec.easing)
}

data class MotionSpec(val ms: Int, val easing: Easing)

/** The light ladder: calm carries no light; each other kind is one meaning (cards/Light.md). */
enum class LightKind { Calm, Now, NeedsYou, Broken }

/** How light arrives, holds and leaves. Stale is not a kind: it is a fade on top of any of them. */
object LightMotion {
    private val needsYouIn = MotionSpec(Motion.MEDIUM2, Motion.EmphasizedDecelerate)
    private val nowIn = MotionSpec(Motion.MEDIUM2, Motion.Standard)
    private val brokenIn = MotionSpec(Motion.SHORT4, Motion.Standard)

    /** A light leaving takes the needs-you → calm row: "light comes faster than it goes". */
    val leave = MotionSpec(Motion.LONG2, Motion.EmphasizedAccelerate)
    val stale = MotionSpec(Motion.EXTRA_LONG4, Motion.Standard)
    val fresh = MotionSpec(Motion.SHORT2, Motion.Standard)
    val focus = MotionSpec(Motion.FOCUS_MS, Motion.Standard)
    val press = MotionSpec(Motion.PRESS_MS, Motion.Standard)

    /** The light [to] arriving; calm is not an arrival. */
    fun arrive(to: LightKind): MotionSpec = when (to) {
        LightKind.NeedsYou -> needsYouIn
        LightKind.Now -> nowIn
        LightKind.Broken -> brokenIn
        LightKind.Calm -> leave
    }

    /** One transition of a card's light: the arrival row of the new kind, or the leaving row when it goes calm. */
    fun transition(from: LightKind, to: LightKind): MotionSpec =
        if (to == LightKind.Calm || from == to) leave else arrive(to)

    /** The spec for one kind's alpha moving toward [active] (1) or away (0). */
    fun alpha(kind: LightKind, active: Boolean): MotionSpec = if (active) arrive(kind) else leave

    fun staleFade(stale: Boolean): MotionSpec = if (stale) this.stale else fresh

    /** Focus dims calm cards only: lit ones and Today (`keepBright`) hold, as `.ph.focus .w:not(.hot):not(.bad):not(.ag)`. */
    fun focusDim(focus: Boolean, kind: LightKind, keepBright: Boolean): Float =
        if (focus && kind == LightKind.Calm && !keepBright) Motion.FOCUS_DIM else 1f
}

fun readAnimatorScale(resolver: ContentResolver): Float =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

/** System animator duration scale, live; 0 is reduced motion ("Remove animations" sets it). */
@Composable
fun rememberAnimatorScale(): Float {
    val resolver = LocalContext.current.contentResolver
    var scale by remember(resolver) { mutableFloatStateOf(readAnimatorScale(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { scale = readAnimatorScale(resolver) }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return scale
}
