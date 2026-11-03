package io.github.egoushka.tin.home.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.egoushka.tin.home.R
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.theme.Lucide
import io.github.egoushka.tin.home.theme.Ng
import io.github.egoushka.tin.home.theme.glass
import io.github.egoushka.tin.home.theme.pressToggleable
import io.github.egoushka.tin.home.tune.Tune

/** A 36 dp disc in a 48 dp target (Controls card). On is fill and glyph colour, not hue: calm has no colour. */
@Composable
internal fun TorchSwitch(on: Boolean, onToggle: (Boolean) -> Unit, tune: Tune, modifier: Modifier = Modifier) {
    // The switch role carries On and Off; the label says what it switches.
    val label = stringResource(R.string.torch)
    Box(
        modifier
            .size(Dimen.target)
            .pressToggleable(on, onToggle)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(Dimen.target - Dimen.space3).then(if (on) Modifier.clip(CircleShape).background(Ng.accent) else Modifier.glass(CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            Image(Lucide.icon("torch", 20f, tune.strokeDp, if (on) Ng.onAccent else Ng.ink), contentDescription = null, Modifier.size(Dimen.glyph))
        }
    }
}
