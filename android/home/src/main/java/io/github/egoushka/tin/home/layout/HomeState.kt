package io.github.egoushka.tin.home.layout

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.github.egoushka.tin.home.apps.AppEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class Picker { None, Widgets, Apps }

/** What Home's stack and pins row show and what edit mode changes; every change is saved as it is made. */
class HomeState(
    private val layouts: LayoutStore,
    private val pinStore: PinStore,
    val widgets: WidgetHost,
    private val scope: CoroutineScope,
) {
    /** Null until the first read, so a hidden card never flashes before the saved layout arrives. */
    var layout by mutableStateOf<HomeLayout?>(null)
        private set
    var pinKeys by mutableStateOf<List<String>?>(null)
        private set
    var editing by mutableStateOf(false)
        private set
    var picker by mutableStateOf(Picker.None)

    init {
        widgets.onAdded = { id, rows -> change { it.addWidget(id, rows) } }
        scope.launch { layouts.layout.collect { layout = it } }
        scope.launch { pinStore.keys.collect { pinKeys = it } }
        scope.launch {
            // Ids the layout never listed (a flow the process died in) and widgets the host lost are cleaned up once per start.
            widgets.sweep(layouts.layout.first().widgetIds)
            layouts.update { it.reconcile(widgets.liveIds()) }
        }
    }

    fun edit(on: Boolean) {
        editing = on
        if (!on) picker = Picker.None
    }

    fun move(slot: Slot, delta: Int) = change { it.move(slot, delta) }
    fun hide(slot: Slot) = change { it.hide(slot) }
    fun show(slot: Slot) = change { it.show(slot) }
    fun resize(id: Int, rows: Int) = change { it.setRows(id, rows) }

    /** The slot goes first, so no view is left holding an id the host has already dropped. */
    fun removeWidget(id: Int) {
        scope.launch {
            layouts.update { it.removeWidget(id) }
            widgets.remove(id)
        }
    }

    fun pin(app: AppEntry) { scope.launch { pinStore.pin(app.key) } }
    fun unpin(key: String) { scope.launch { pinStore.unpin(key) } }

    private fun change(f: (HomeLayout) -> HomeLayout) { scope.launch { layouts.update(f) } }

    companion object {
        fun of(activity: ComponentActivity) =
            HomeState(LayoutStore.of(activity), PinStore.of(activity), WidgetHost(activity), activity.lifecycleScope)
    }
}
