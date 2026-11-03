package io.github.egoushka.tin.home.pages

/** Where an item was drawn, in the pager's own coordinates. */
data class Spot(val id: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2
}

enum class Edge { None, Left, Right }

/** Plain geometry for a drag, so no phone is needed to check where a drop lands. */
object PageDrop {
    /** A starting value: a thumb's width, the same as the side gesture edge. */
    const val EDGE_DP = 48

    /** How long the finger stays in an edge zone before the page flips, then again for every further page. */
    const val HOLD_MS = 600L

    fun edge(x: Float, width: Float, zone: Float): Edge = when {
        x < zone -> Edge.Left
        x > width - zone -> Edge.Right
        else -> Edge.None
    }

    /**
     * The id the dragged item goes in front of when dropped at ([x], [y]), or null for the end of the page.
     * An item is passed when the finger is below its row, or in its row and past its middle; so a drop on the left
     * half of a tile goes before it, on the right half after it, between rows after the row above, and below
     * everything at the end. The dragged item itself is skipped.
     */
    fun before(spots: List<Spot>, dragged: String, x: Float, y: Float): String? =
        spots.filter { it.id != dragged }.firstOrNull { !passed(it, x, y) }?.id

    private fun passed(spot: Spot, x: Float, y: Float): Boolean = when {
        y >= spot.bottom -> true
        y < spot.top -> false
        else -> x >= spot.centerX
    }
}
