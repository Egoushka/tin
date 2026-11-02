package io.github.egoushka.tin.home.layout

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import io.github.egoushka.tin.home.theme.Dimen
import kotlin.math.abs

object FrameTags {
    const val Stack = "home.stack"
}

/** Screen areas where Home's own gestures do not start: a widget keeps its swipes and long-presses. */
class NoGestureZones {
    private val zones = mutableMapOf<Any, Rect>()

    fun set(key: Any, bounds: Rect) { zones[key] = bounds }
    fun clear(key: Any) { zones.remove(key) }
    operator fun contains(p: Offset) = zones.values.any { it.contains(p) }
}

val LocalNoGestureZones = staticCompositionLocalOf<NoGestureZones?> { null }

fun Modifier.noHomeGestures(): Modifier = composed {
    val zones = LocalNoGestureZones.current
    val key = remember { Any() }
    DisposableEffect(zones) { onDispose { zones?.clear(key) } }
    onGloballyPositioned { zones?.set(key, it.boundsInRoot()) }
}

/**
 * Home's frame: [top] and [bottom] keep their size, the [stack] takes what is left and scrolls, so the dock and the
 * search bar never leave the thumb's reach. Swipes up and down open the layers, but a swipe that starts on the stack
 * only counts when the stack was already at that end; the 24 dp side edges belong to Android's back gesture.
 */
@Composable
fun HomeFrame(
    gap: Dp,
    gestures: Boolean,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit,
    top: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
    stack: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val edge = with(density) { Dimen.backEdge.toPx() }
    val travel = with(density) { Dimen.target.toPx() } // one target's travel opens a layer
    val scroll = rememberScrollState()
    val zones = remember { NoGestureZones() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var stackBounds by remember { mutableStateOf(Rect.Zero) }
    val up by rememberUpdatedState(onSwipeUp)
    val down by rememberUpdatedState(onSwipeDown)
    val double by rememberUpdatedState(onDoubleTap)
    val long by rememberUpdatedState(onLongPress)

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(gestures) {
                if (!gestures) return@pointerInput
                // Watches from the Initial pass and consumes nothing, so the stack still scrolls under the same finger.
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val at = origin + first.position
                    val live = first.position.x > edge && first.position.x < size.width - edge && at !in zones
                    val onStack = stackBounds.contains(at)
                    val atTop = !scroll.canScrollBackward
                    val atBottom = !scroll.canScrollForward
                    var dy = 0f
                    var lifted = false
                    var second = false
                    // A press held still on the stack is a long-press. Cards own their taps, so it is read here, before them.
                    val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == first.id }
                            if (change == null || event.changes.size > 1) { second = true; return@withTimeoutOrNull }
                            dy = change.position.y - first.position.y
                            if (change.changedToUpIgnoreConsumed()) { lifted = true; return@withTimeoutOrNull }
                            if ((change.position - first.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull
                        }
                    } == null
                    if (second) return@awaitEachGesture
                    if (held && live && onStack) {
                        long()
                        // Consumed from here on, the card under the finger sees a cancel, not a tap on release.
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        return@awaitEachGesture
                    }
                    while (!lifted) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.size > 1) return@awaitEachGesture
                        val change = event.changes.firstOrNull { it.id == first.id } ?: return@awaitEachGesture
                        dy = change.position.y - first.position.y
                        if (change.changedToUpIgnoreConsumed()) lifted = true
                    }
                    if (!live || abs(dy) < travel) return@awaitEachGesture
                    val upward = dy < 0
                    if (onStack && !(if (upward) atBottom else atTop)) return@awaitEachGesture
                    if (upward) up() else down()
                }
            }
            .pointerInput(gestures) {
                if (!gestures) return@pointerInput
                detectTapGestures(
                    onDoubleTap = { if (it.x > edge && it.x < size.width - edge && origin + it !in zones) double() },
                    onLongPress = {
                        val at = origin + it
                        // A press on the stack was already read from the Initial pass above.
                        if (it.x > edge && it.x < size.width - edge && at !in zones && !stackBounds.contains(at)) long()
                    },
                )
            },
    ) {
        CompositionLocalProvider(LocalNoGestureZones provides zones) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = Dimen.gutter),
            ) {
                top()
                Spacer(Modifier.height(gap + Dimen.space2))
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag(FrameTags.Stack)
                        .onGloballyPositioned { stackBounds = it.boundsInRoot() }
                        .verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(gap),
                    content = stack,
                )
                Spacer(Modifier.height(gap + Dimen.space2))
                bottom()
            }
        }
    }
}
