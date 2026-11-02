package io.github.egoushka.tin.home.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.egoushka.tin.home.R
import io.github.egoushka.tin.home.layer.Freshness
import io.github.egoushka.tin.home.layer.ageText
import io.github.egoushka.tin.home.signals.Sky
import io.github.egoushka.tin.home.signals.WeatherSource
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.theme.Lucide
import io.github.egoushka.tin.home.theme.LocalTune
import io.github.egoushka.tin.home.theme.Motion
import io.github.egoushka.tin.home.theme.Ng
import io.github.egoushka.tin.home.theme.NgType

private val MAX_WIDTH = 160.dp // 40 grid steps: the date's column never squeezes the clock

/**
 * Under the date: icon, whole degrees and one word, e.g. "8° Fog". Quiet until the first answer; with no value yet and
 * no answer it says so at the stale opacity, never a number. Ages in only after a missed refresh, fades after three.
 * [now] is Home's minute tick, which also asks the source to catch up after the CPU slept.
 */
@Composable
fun WeatherLine(source: WeatherSource, now: Long, modifier: Modifier = Modifier) {
    LaunchedEffect(now) { source.refreshIfDue() }
    val state by source.state.collectAsState()
    if (state.status == WeatherSource.Status.Loading) return
    val weather = state.weather
    val dim = weather == null || state.isStale(now)
    val scale = animatorScale()
    val fade by animateFloatAsState(
        if (dim) Motion.STALE_ALPHA else 1f,
        tween(Motion.duration(if (dim) Motion.STALE_IN else Motion.FRESH_IN, scale)), label = "weatherStale",
    )
    Row(modifier.widthIn(max = MAX_WIDTH).alpha(fade), verticalAlignment = Alignment.CenterVertically) {
        if (weather == null) {
            Text(stringResource(R.string.weather_unavailable), style = NgType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            return@Row
        }
        weather.icon?.let {
            Image(Lucide.icon(it, 16f, LocalTune.current.strokeDp, Ng.ink2), null, Modifier.size(Dimen.glyphSmall))
            Spacer(Modifier.width(Dimen.space1))
        }
        val word = weather.sky?.let { stringResource(it.label) }
        Text(
            if (word == null) "${weather.celsius}°" else "${weather.celsius}° $word",
            style = NgType.body.copy(color = Ng.ink), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (state.showsAge(now)) {
            Spacer(Modifier.width(Dimen.space1))
            Text("· ${ageText(Freshness.age(now, state.at))}", style = NgType.body, maxLines = 1)
        }
    }
}

@get:StringRes
private val Sky.label: Int
    get() = when (this) {
        Sky.Clear -> R.string.weather_clear
        Sky.PartlyCloudy -> R.string.weather_partly
        Sky.Cloudy -> R.string.weather_cloudy
        Sky.Fog -> R.string.weather_fog
        Sky.Drizzle -> R.string.weather_drizzle
        Sky.Rain -> R.string.weather_rain
        Sky.Snow -> R.string.weather_snow
        Sky.Thunder -> R.string.weather_thunder
    }
