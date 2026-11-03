package io.github.egoushka.tin.home.gestures

import android.content.Intent
import io.github.egoushka.tin.home.DockDefault
import io.github.egoushka.tin.home.apps.AppShortcut

/** What a swipe up on a dock tile does. */
sealed interface DockSwipe {
    /** The app's own shortcut. */
    data class Shortcut(val shortcut: AppShortcut) : DockSwipe

    /** The slot's default job, while no app sits in it. */
    data class Open(val intent: Intent) : DockSwipe

    companion object {
        /** An app's first listed shortcut; an empty slot takes its default's swipe; null when there is none, and the frame's swipe applies. */
        fun of(hasApp: Boolean, d: DockDefault, shortcuts: List<AppShortcut>): DockSwipe? =
            if (hasApp) shortcuts.firstOrNull()?.let(::Shortcut) else d.swipe?.let { Open(it()) }
    }
}
