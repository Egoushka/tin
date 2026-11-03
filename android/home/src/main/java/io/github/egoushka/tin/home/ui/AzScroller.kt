package io.github.egoushka.tin.home.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import io.github.egoushka.tin.home.R
import io.github.egoushka.tin.home.apps.AzIndex
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.theme.Ng
import io.github.egoushka.tin.home.theme.NgType

/**
 * A strip of the first letters on the right edge: a tap or a drag along it calls [onJump] with the position of the
 * first app under that letter. A screen reader gets one button per letter instead of a gesture.
 */
@Composable
fun AzScroller(index: AzIndex, onJump: (position: Int) -> Unit, modifier: Modifier = Modifier) {
    if (index.letters.size < 2) return
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier
            .width(Dimen.target)
            .fillMaxHeight()
            .pointerInput(index) {
                detectTapGestures { at -> index.at(at.y, size.height.toFloat())?.let { onJump(it.position) } }
            }
            .pointerInput(index) {
                detectVerticalDragGestures { change, _ -> index.at(change.position.y, size.height.toFloat())?.let { onJump(it.position) } }
            }
            .onSizeChanged { size = it },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
            index.letters.forEach { letter ->
                val description = stringResource(R.string.az_jump, letter.char.toString())
                Text(
                    letter.char.toString(),
                    style = NgType.meta.copy(color = Ng.ink2),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .width(Dimen.target)
                        .semantics {
                            contentDescription = description
                            role = Role.Button
                            onClick { onJump(letter.position); true }
                        },
                )
            }
        }
    }
}
