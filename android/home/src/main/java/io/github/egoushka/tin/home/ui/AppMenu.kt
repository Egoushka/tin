package io.github.egoushka.tin.home.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.egoushka.tin.home.apps.AppEntry
import io.github.egoushka.tin.home.pages.PagePlace

/** What a long press asks of the screen, which owns the sheet and the pick mode; tiles only call these. */
@Stable
class AppMenu(
    /** [dockSlot] is where the app sits in the dock, null for a drawer tile. */
    val open: (app: AppEntry, dockSlot: Int?) -> Unit,
    /** Opens All apps to choose the app for dock slot [slot]. */
    val pick: (slot: Int) -> Unit,
    /** The press was on a tile of an extra page, which adds moving and removing it to the menu. */
    val openOnPage: (app: AppEntry, place: PagePlace) -> Unit = { _, _ -> },
)

val LocalAppMenu = staticCompositionLocalOf { AppMenu(open = { _, _ -> }, pick = {}) }

/** While set, All apps is choosing the app for [slot]: a tap docks the app instead of opening it. */
@Stable
class DockPick(val slot: Int, val put: (AppEntry) -> Unit)

val LocalDockPick = compositionLocalOf<DockPick?> { null }
