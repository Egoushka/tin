package io.github.egoushka.tin.home.pages

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import io.github.egoushka.tin.home.layout.HomeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where a tile or widget on an extra page was last laid out; [PageDrag] reads it when a finger goes down or moves. */
class SpotNode {
    var page: Int = -1
    var id: String = ""
    var coords: LayoutCoordinates? = null
}

/**
 * The state of one lift-and-drop on the extra pages. [pointer] is the finger in the container's own coordinates, where the
 * ghost is drawn; hit tests use root coordinates, which is where the tiles report themselves.
 */
@Stable
class PageDrag {
    var lifted by mutableStateOf<PageItem?>(null)
        private set

    /** The extra page under the finger, or null on the main page (a drop there changes nothing). */
    var over by mutableStateOf<Int?>(null)
        private set

    /** The id the item would go in front of, or null for the end of [over]. */
    var before by mutableStateOf<String?>(null)
        private set

    var pointer by mutableStateOf(Offset.Zero)
        private set

    var origin: Offset = Offset.Zero

    private var from = -1
    private var edge = Edge.None
    private var flipping: Job? = null
    private val nodes = mutableSetOf<SpotNode>()

    internal fun add(node: SpotNode) { nodes.add(node) }
    internal fun remove(node: SpotNode) { nodes.remove(node) }

    private fun spotsOn(page: Int): List<Spot> = nodes.mapNotNull { node ->
        val coords = node.coords?.takeIf { it.isAttached && node.page == page } ?: return@mapNotNull null
        val r = coords.boundsInRoot()
        Spot(node.id, r.left, r.top, r.right, r.bottom)
    }

    /** The id of the item drawn at [root] on extra page [page]. */
    fun hit(root: Offset, page: Int): String? =
        spotsOn(page).firstOrNull { root.x >= it.left && root.x < it.right && root.y >= it.top && root.y < it.bottom }?.id

    fun lift(item: PageItem, page: Int, at: Offset) {
        lifted = item
        from = page
        over = page
        pointer = at
        before = null
    }

    fun hover(at: Offset, page: Int?) {
        val item = lifted ?: return
        pointer = at
        over = page
        before = if (page == null) null else PageDrop.before(spotsOn(page), item.id, at.x + origin.x, at.y + origin.y)
    }

    /** Starts or ends the hold on a screen edge; while it lasts [flip] is asked for a page every [PageDrop.HOLD_MS]. */
    fun edge(edge: Edge, scope: CoroutineScope, flip: suspend (direction: Int) -> Unit) {
        if (edge == this.edge) return
        this.edge = edge
        flipping?.cancel()
        flipping = if (edge == Edge.None) null else scope.launch {
            while (true) {
                delay(PageDrop.HOLD_MS)
                flip(if (edge == Edge.Right) 1 else -1)
            }
        }
    }

    fun drop(home: HomeState) {
        val item = lifted
        val page = over
        val ahead = before
        val source = from
        cancel()
        if (item != null && page != null) home.moveItem(source, item.id, page, ahead)
    }

    fun cancel() {
        flipping?.cancel()
        flipping = null
        edge = Edge.None
        lifted = null
        over = null
        before = null
        from = -1
    }
}

/** Reports the modified item's bounds to [drag], so a long press knows what it is on and a drop knows what it is between. */
fun Modifier.pageSpot(drag: PageDrag, page: Int, id: String): Modifier = composed {
    val node = remember { SpotNode() }
    SideEffect { node.page = page; node.id = id }
    DisposableEffect(drag, node) {
        drag.add(node)
        onDispose { drag.remove(node) }
    }
    onGloballyPositioned { node.coords = it }
}

/**
 * Lifts the item under a long press on an extra page and carries it: it follows the finger over [pager], a hold at either
 * edge turns the page, and letting go drops it. Taps and swipes that do not become a long press are not touched.
 */
fun Modifier.pageDrag(
    drag: PageDrag,
    home: HomeState,
    pager: PagerState,
    scope: CoroutineScope,
    haptic: HapticFeedback,
    enabled: Boolean,
): Modifier = onGloballyPositioned { drag.origin = it.positionInRoot() }
    .pointerInput(enabled, pager) {
        if (!enabled) return@pointerInput
        val zone = PageDrop.EDGE_DP.dp.toPx()
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val page = pager.currentPage - 1
            if (page < 0) return@awaitEachGesture
            val id = drag.hit(down.position + drag.origin, page) ?: return@awaitEachGesture
            val item = home.pages.pages.getOrNull(page)?.firstOrNull { it.id == id } ?: return@awaitEachGesture

            var ended = false
            val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (!ended) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                    ended = change == null || change.changedToUpIgnoreConsumed() ||
                        (change.position - down.position).getDistance() > slop
                }
            } == null
            if (!held) return@awaitEachGesture

            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            drag.lift(item, page, down.position)
            val flip: suspend (Int) -> Unit = { direction ->
                val target = pager.currentPage + direction
                if (target in 1 until pager.pageCount) {
                    pager.animateScrollToPage(target)
                    drag.hover(drag.pointer, pager.currentPage - 1)
                }
            }
            try {
                var live = true
                while (live) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        live = false
                    } else {
                        change.consume()
                        if (change.changedToUpIgnoreConsumed()) {
                            live = false
                        } else {
                            drag.hover(change.position, (pager.currentPage - 1).takeIf { it >= 0 })
                            drag.edge(PageDrop.edge(change.position.x, size.width.toFloat(), zone), scope, flip)
                        }
                    }
                }
                drag.drop(home)
            } finally {
                drag.cancel()
            }
        }
    }
