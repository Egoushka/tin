package io.github.egoushka.tin.home.layer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.egoushka.tin.home.R
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.theme.GlassPaint
import io.github.egoushka.tin.home.theme.LightKind
import io.github.egoushka.tin.home.theme.LightMotion
import io.github.egoushka.tin.home.theme.LocalTune
import io.github.egoushka.tin.home.theme.Motion
import io.github.egoushka.tin.home.theme.Ng
import io.github.egoushka.tin.home.theme.NgType
import io.github.egoushka.tin.home.theme.glassBody
import io.github.egoushka.tin.home.theme.lerpColor
import io.github.egoushka.tin.home.theme.rememberAnimatorScale
import io.github.egoushka.tin.home.theme.softShadow
import kotlinx.coroutines.delay
import kotlin.math.min

/** What a card says about itself: its place on the attention ladder, and whether its source has gone quiet. */
data class CardLight(val kind: LightKind = LightKind.Calm, val stale: Boolean = false)

private val CardShape = RoundedCornerShape(Dimen.radiusCard)

/** The wall clock, advancing every 30 s so ages and "in 18 min" stay true without a per-card timer. */
@Composable
fun rememberNow(): Long = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(30_000)
        value = System.currentTimeMillis()
    }
}.value

@Composable
fun ageText(age: Age): String = when (age.unit) {
    AgeUnit.Now -> stringResource(R.string.age_now)
    AgeUnit.Min -> stringResource(R.string.age_min, age.n)
    AgeUnit.Hour -> stringResource(R.string.age_hour, age.n)
    AgeUnit.Day -> stringResource(R.string.age_day, age.n)
}

/**
 * The shared glass card: label and source age on the first line, then [content]. Light moves with the table of
 * cards/Light.md (theme/Motion.kt); stale fades to 55%; focus dims calm cards to 40% unless [keepBright] (Today).
 * Every animated value is read while drawing, so a light changing redraws the card and recomposes nothing.
 */
@Composable
fun LayerCard(
    label: String,
    age: Age?,
    light: CardLight,
    modifier: Modifier = Modifier,
    keepBright: Boolean = false,
    content: @Composable () -> Unit,
) {
    val tune = LocalTune.current
    val scale = rememberAnimatorScale()
    val needsYou = animateFloatAsState(
        if (light.kind == LightKind.NeedsYou) 1f else 0f,
        Motion.tween(LightMotion.alpha(LightKind.NeedsYou, light.kind == LightKind.NeedsYou), scale), label = "needs you",
    )
    val broken = animateFloatAsState(
        if (light.kind == LightKind.Broken) 1f else 0f,
        Motion.tween(LightMotion.alpha(LightKind.Broken, light.kind == LightKind.Broken), scale), label = "broken",
    )
    val fade = animateFloatAsState(
        if (light.stale) Motion.STALE_ALPHA else 1f, Motion.tween(LightMotion.staleFade(light.stale), scale), label = "stale",
    )
    val dim = animateFloatAsState(
        LightMotion.focusDim(tune.focus, light.kind, keepBright), Motion.tween(LightMotion.focus, scale), label = "focus",
    )
    // CSS sets opacity once on the element: the focus rule outranks `.stale`, so the two never multiply.
    val opacity = { min(fade.value, dim.value) }
    val attention = tune.attention.color
    Column(
        modifier
            .fillMaxWidth()
            .softShadow(CardShape, Color.Black.copy(alpha = GlassPaint.DROP_ALPHA), GlassPaint.DROP_DY, GlassPaint.DROP_BLUR,
                GlassPaint.DROP_SPREAD, opacity)
            .softShadow(CardShape, attention, 0f, GlassPaint.HALO_BLUR, GlassPaint.HALO_SPREAD) { needsYou.value * opacity() }
            .graphicsLayer { alpha = opacity() }
            .glassBody(CardShape, attention, { needsYou.value }, { broken.value })
            .padding(tune.padDp.dp),
        verticalArrangement = Arrangement.spacedBy(Dimen.space2),
    ) {
        if (label.isNotEmpty()) CardHeader(label, age, needsYou)
        content()
    }
}

/** A lit card's label turns from ink-2 to ink; reading [lit] here keeps that recomposition off the card body. */
@Composable
private fun CardHeader(label: String, age: Age?, lit: State<Float>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label.uppercase(), style = NgType.label, color = lerpColor(Ng.ink2, Ng.ink, lit.value))
        if (age != null) Text(ageText(age), style = NgType.meta)
    }
}
