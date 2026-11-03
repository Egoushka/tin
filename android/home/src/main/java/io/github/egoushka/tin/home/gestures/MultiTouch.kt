package io.github.egoushka.tin.home.gestures

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs

/** What two fingers did between landing and the first one lifting. */
enum class TwoFinger {
    SwipeUp, SwipeDown, PinchIn;

    companion object {
        private const val PINCH = 0.7f // the span closes to this share of where it began, or less
        private const val STRAIGHT = 1.5f // up or down must beat sideways by this much

        /** From where the two fingers [a0] and [b0] landed to where they were last seen, [a1] and [b1]; null when it was none of them. */
        fun of(a0: Offset, b0: Offset, a1: Offset, b1: Offset, travel: Float): TwoFinger? {
            val da = a1 - a0
            val db = b1 - b0
            val middle = (da + db) / 2f
            val together = da.y * db.y > 0f && minOf(abs(da.y), abs(db.y)) >= travel / 2f
            if (together && abs(middle.y) >= travel && abs(middle.y) > STRAIGHT * abs(middle.x)) {
                return if (middle.y < 0f) SwipeUp else SwipeDown
            }
            val before = (b0 - a0).getDistance()
            val after = (b1 - a1).getDistance()
            if (before > 0f && after <= before * PINCH && middle.getDistance() < travel / 2f) return PinchIn
            return null
        }
    }
}
