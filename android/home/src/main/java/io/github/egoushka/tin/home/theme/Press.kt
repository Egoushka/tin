package io.github.egoushka.tin.home.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role

/** One control's press, 0 (up) to 1 (down), eased with the press row of the motion table. */
@Stable
private class Press(val source: MutableInteractionSource, val amount: State<Float>)

@Composable
private fun rememberPress(): Press {
    val source = remember { MutableInteractionSource() }
    val down by source.collectIsPressedAsState()
    val amount = animateFloatAsState(if (down) 1f else 0f, Motion.tween(LightMotion.press, rememberAnimatorScale()), label = "press")
    return remember(source) { Press(source, amount) }
}

private fun Modifier.pressScale(press: Press): Modifier = graphicsLayer {
    val s = 1f - (1f - Motion.PRESS_SCALE) * press.amount.value
    scaleX = s
    scaleY = s
}

/** Press feedback of `.sb-mic:active` and `.ic:active i`: no ripple, the control scales to .92 over 160 ms. */
fun Modifier.pressable(role: Role = Role.Button, onClick: () -> Unit): Modifier = composed {
    val press = rememberPress()
    this.clickable(press.source, null, role = role, onClick = onClick).pressScale(press)
}

/** A dock tile: [pressable], and the glass takes `.ic:active i`'s surface-2 fill and edge-hi border. */
fun Modifier.pressGlass(
    shape: Shape,
    role: Role = Role.Button,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val press = rememberPress()
    this.combinedClickable(press.source, null, role = role, onLongClickLabel = onLongClickLabel, onLongClick = onLongClick, onClick = onClick)
        .pressScale(press)
        .glassBody(shape, pressed = { press.amount.value })
}

/** A tap with nothing drawn for it: Night glass gives the search bar and the cards no pressed state. */
fun Modifier.plainClickable(role: Role = Role.Button, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier = composed {
    this.combinedClickable(remember { MutableInteractionSource() }, null, role = role, onLongClick = onLongClick, onClick = onClick)
}
