package io.github.egoushka.tin.home.gestures

import androidx.annotation.StringRes
import io.github.egoushka.tin.home.R

/** What a gesture can do on Home; [Nothing] is the off switch. */
enum class GestureAction(@StringRes val label: Int, val icon: String) {
    AllApps(R.string.gesture_do_apps, "search"),
    Notifications(R.string.gesture_do_notifications, "bell"),
    QuickSettings(R.string.gesture_do_quick_settings, "sliders"),
    Camera(R.string.gesture_do_camera, "camera"),
    Torch(R.string.torch, "torch"),
    Today(R.string.gesture_do_today, "cal"),
    EditHome(R.string.gesture_do_edit, "pin"),
    Nothing(R.string.gesture_do_nothing, "x");

    /** The one after this in the sheet's list, wrapping to the first. */
    fun next(): GestureAction = entries[(ordinal + 1) % entries.size]

    fun run(effects: GestureEffects) {
        when (this) {
            AllApps -> effects.search()
            Notifications -> effects.notifications()
            QuickSettings -> effects.quickSettings()
            Camera -> effects.camera()
            Torch -> effects.torch()
            Today -> effects.today()
            EditHome -> effects.editHome()
            Nothing -> Unit
        }
    }
}

/** The gestures that start on empty Home space and can be reassigned; the search bar and the dock tiles keep theirs. */
enum class Gesture(val default: GestureAction, @StringRes val label: Int) {
    SwipeUp(GestureAction.AllApps, R.string.gesture_swipe_up),
    SwipeDown(GestureAction.Notifications, R.string.gesture_swipe_down),
    TwoFingerDown(GestureAction.QuickSettings, R.string.gesture_two_down),
    TwoFingerUp(GestureAction.Camera, R.string.gesture_two_up),
    DoubleTap(GestureAction.Torch, R.string.gesture_double_tap),
    PinchIn(GestureAction.Today, R.string.gesture_pinch),
    LongPress(GestureAction.EditHome, R.string.gesture_long_press),
}

/** The steps Home can take; Home implements them, an action picks one. */
interface GestureEffects {
    fun search()
    fun notifications()
    fun quickSettings()
    fun camera()
    fun torch()
    fun today()
    fun editHome()
}

/** Which action each gesture runs. Only the changes from the defaults are held, so a new default reaches everyone who never chose. */
data class GestureMap(private val changed: Map<Gesture, GestureAction> = emptyMap()) {
    fun of(g: Gesture): GestureAction = changed[g] ?: g.default

    fun with(g: Gesture, a: GestureAction): GestureMap =
        GestureMap(if (a == g.default) changed - g else changed + (g to a))

    fun encode(): String = changed.entries.sortedBy { it.key.ordinal }.joinToString(",") { "${it.key.name}:${it.value.name}" }

    companion object {
        /** An unknown gesture, an unknown action or a malformed entry is skipped, so it reads as the default. */
        fun decode(s: String?): GestureMap {
            var map = GestureMap()
            s.orEmpty().split(',').forEach { entry ->
                val (g, a) = entry.split(':').takeIf { it.size == 2 } ?: return@forEach
                val gesture = Gesture.entries.firstOrNull { it.name == g } ?: return@forEach
                val action = GestureAction.entries.firstOrNull { it.name == a } ?: return@forEach
                map = map.with(gesture, action)
            }
            return map
        }
    }
}
