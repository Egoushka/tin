package io.github.egoushka.tin.home.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import io.github.egoushka.tin.home.gestures.Gesture
import io.github.egoushka.tin.home.gestures.GestureAction
import io.github.egoushka.tin.home.gestures.GestureMap
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.theme.LocalTune
import io.github.egoushka.tin.home.theme.Lucide
import io.github.egoushka.tin.home.theme.Ng
import io.github.egoushka.tin.home.theme.NgType
import io.github.egoushka.tin.home.tune.Tune

/**
 * What each gesture of empty Home space does. A tap on a row picks the next action in the list, wrapping; [onPick] saves it
 * at once, so closing the sheet leaves the map as the last tap made it and Back alone changes nothing.
 */
@Composable
fun GestureSheet(tune: Tune, map: GestureMap, onPick: (Gesture, GestureAction) -> Unit, onClose: () -> Unit) {
    val stroke = LocalTune.current.strokeDp
    SheetFrame(tune, onClose) {
        Row(Modifier.padding(start = Dimen.space3), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.gestures), style = NgType.title, modifier = Modifier.weight(1f))
            IconButton("x", stringResource(R.string.close), onClose)
        }
        Gesture.entries.forEach { g ->
            val action = map.of(g)
            val gesture = stringResource(g.label)
            val does = stringResource(action.label)
            val description = stringResource(R.string.gesture_row, gesture, does)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimen.target)
                    .clip(RoundedCornerShape(Dimen.radiusRow))
                    .clickable(role = Role.Button) { onPick(g, action.next()) }
                    .semantics { contentDescription = description }
                    .padding(horizontal = Dimen.space3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(Lucide.icon(action.icon, 16f, stroke), contentDescription = null, Modifier.size(Dimen.glyphSmall))
                Spacer(Modifier.width(Dimen.space3))
                Text(gesture, style = NgType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).clearAndSetSemantics {})
                Spacer(Modifier.width(Dimen.space2))
                Text(does, style = NgType.body.copy(color = Ng.ink2), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics {})
            }
        }
        Text(stringResource(R.string.gestures_hint), style = NgType.body, modifier = Modifier.padding(Dimen.space3))
    }
}
