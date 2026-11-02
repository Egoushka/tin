package io.github.egoushka.tin.home.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.egoushka.tin.home.R
import io.github.egoushka.tin.home.apps.AppRepository
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.theme.LocalTune
import io.github.egoushka.tin.home.theme.Ng
import io.github.egoushka.tin.home.theme.NgType
import io.github.egoushka.tin.home.theme.glass

/** The apps hidden from the drawer (reached from Tune); a tap on a row shows the app again. */
@Composable
fun HiddenSheet(apps: AppRepository, onShow: (key: String) -> Unit, onClose: () -> Unit) {
    val tune = LocalTune.current
    val state by apps.state.collectAsState()
    SheetFrame(tune, onClose) {
        Row(Modifier.padding(start = Dimen.space3), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tune_hidden), style = NgType.title, modifier = Modifier.weight(1f))
            IconButton("x", stringResource(R.string.close), onClose)
        }
        if (state.hiddenApps.isEmpty()) {
            Text(stringResource(R.string.hidden_none), style = NgType.body, modifier = Modifier.padding(Dimen.space3))
        }
        state.hiddenApps.forEach { app ->
            key(app.key) {
                val description = stringResource(R.string.hidden_show_app, app.label)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimen.target)
                        .clip(RoundedCornerShape(Dimen.radiusRow))
                        .clickable(role = Role.Button) { onShow(app.key) }
                        .semantics { contentDescription = description }
                        .padding(horizontal = Dimen.space3, vertical = Dimen.space1),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(Dimen.tile).glass(CircleShape), contentAlignment = Alignment.Center) {
                        AppIcon(app, apps, Modifier.size(Dimen.glyphTile * 1.5f))
                    }
                    Spacer(Modifier.width(Dimen.space3))
                    Text(
                        app.label, style = NgType.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clearAndSetSemantics {},
                    )
                    Text(
                        stringResource(R.string.hidden_show).uppercase(), style = NgType.label.copy(color = Ng.ink),
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
        }
    }
}
