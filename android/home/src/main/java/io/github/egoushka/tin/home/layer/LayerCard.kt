package io.github.egoushka.tin.home.layer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import io.github.egoushka.tin.home.R
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.theme.LocalTune
import io.github.egoushka.tin.home.theme.Motion
import io.github.egoushka.tin.home.theme.NgType
import io.github.egoushka.tin.home.theme.glass
import io.github.egoushka.tin.home.ui.animatorScale
import kotlinx.coroutines.delay

/** What a card says about itself; [light] is the attention ladder (null calm, glow-1 needs you, bad broken, signal now). */
data class CardLight(val color: Color?, val stale: Boolean = false)

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
 * The shared glass card: label and source age on the first line, then [content]. Light eases in and out with the
 * durations of Light.md; stale fades to 55%; focus dims calm cards to 40%.
 */
@Composable
fun LayerCard(
    label: String,
    age: Age?,
    light: CardLight,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tune = LocalTune.current
    val scale = animatorScale()
    val lit = light.color != null
    val tween = tween<Float>(Motion.duration(if (lit) Motion.NEEDS_YOU_IN else Motion.NEEDS_YOU_OUT, scale))
    val glow by animateFloatAsState(if (lit) 1f else 0f, tween, label = "light")
    // The colour outlives the state so the light can fade out instead of vanishing.
    var lastLight by remember { mutableStateOf(Color.Transparent) }
    if (light.color != null) lastLight = light.color
    val fade by animateFloatAsState(
        if (light.stale) Motion.STALE_ALPHA else 1f,
        tween(Motion.duration(if (light.stale) Motion.STALE_IN else Motion.FRESH_IN, scale)), label = "stale",
    )
    val dim = if (tune.focus && !lit) Motion.FOCUS_DIM else 1f
    Column(
        modifier
            .fillMaxWidth()
            .alpha(fade * dim)
            .glass(RoundedCornerShape(Dimen.radiusCard), if (glow > 0f) lastLight.copy(alpha = glow) else null)
            .padding(tune.padDp.dp + Dimen.space1),
        verticalArrangement = Arrangement.spacedBy(Dimen.space2),
    ) {
        if (label.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label.uppercase(), style = NgType.label)
                if (age != null) Text(ageText(age), style = NgType.label.copy(letterSpacing = TextUnit.Unspecified))
            }
        }
        content()
    }
}
