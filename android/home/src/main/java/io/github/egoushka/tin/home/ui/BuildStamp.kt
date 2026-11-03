package io.github.egoushka.tin.home.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.egoushka.tin.home.BuildConfig
import io.github.egoushka.tin.home.R
import io.github.egoushka.tin.home.theme.NgType

/** "Build 0.1.26 · e9d6b0b": the same pair as the latest release on GitHub, so "am I on the latest?" is a look. */
@Composable
fun BuildStamp(modifier: Modifier = Modifier) {
    Text(stringResource(R.string.build_stamp, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA), style = NgType.label, modifier = modifier)
}
