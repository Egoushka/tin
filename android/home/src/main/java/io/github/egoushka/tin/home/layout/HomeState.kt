package io.github.egoushka.tin.home.layout

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.github.egoushka.tin.home.apps.AppEntry
import io.github.egoushka.tin.home.apps.AppRepository
import io.github.egoushka.tin.home.pages.PageItem
import io.github.egoushka.tin.home.pages.PageStore
import io.github.egoushka.tin.home.pages.Pages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class Picker { None, Widgets, Apps }

/** What Home's stack, pages and pins row show and what edit mode changes; every change is saved as it is made. */
class HomeState(
    private val layouts: LayoutStore,
    private val pageStore: PageStore,
    private val pinStore: PinStore,
    val widgets: WidgetHost,
    private val scope: CoroutineScope,
    /** Sets the pinned shortcuts of an app; Android keeps a shortcut that is pinned even if its app drops it. */
    private val pinShortcuts: suspend (pkg: String, user: Long, ids: Set<String>) -> Unit = { _, _, _ -> },
) {
    /** Null until the first read, so a hidden card never flashes before the saved layout arrives. */
    var layout by mutableStateOf<HomeLayout?>(null)
        private set
    var pages by mutableStateOf(Pages())
        private set
    var pinKeys by mutableStateOf<List<String>?>(null)
        private set
    var editing by mutableStateOf(false)
        private set
    var picker by mutableStateOf(Picker.None)
        private set

    /** The extra page the open picker adds to; null is the main page. */
    var pickerPage by mutableStateOf<Int?>(null)
        private set

    /** The page the running widget flow adds to; null is the main page. */
    private var widgetPage: Int? = null

    init {
        widgets.onAdded = { id, rows ->
            val page = widgetPage
            widgetPage = null
            if (page == null) change { it.addWidget(id, rows) } else placeWidget(page, id, rows)
        }
        scope.launch {
            // One collector for both, so a widget moving between the stack and a page is never on neither.
            pageStore.board.collect { layout = it.layout; pages = it.pages }
        }
        scope.launch { pinStore.keys.collect { pinKeys = it } }
        scope.launch {
            // Ids no list holds (a flow the process died in) and widgets the host lost are cleaned up once per start.
            widgets.sweep(pageStore.board.first().widgetIds)
            pageStore.updateBoard { it.reconcile(widgets.liveIds()) }
        }
    }

    fun seedPins(defaults: List<String>) {
        scope.launch { pinStore.seed(defaults) }
    }

    fun edit(on: Boolean) {
        editing = on
        if (!on) picker = Picker.None
    }

    fun openPicker(kind: Picker, page: Int?) {
        pickerPage = page
        picker = kind
    }

    fun closePicker() { picker = Picker.None }

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

    /** Starts the widget flow; the result lands on [page], or on the main page when it is null. */
    fun addWidget(provider: ComponentName, page: Int?) {
        widgetPage = page
        widgets.add(provider)
    }

    fun addPage() = editPages { it.addPage() }

    fun removePage(page: Int) {
        val gone = pages.pages.getOrNull(page).orEmpty().filterIsInstance<PageItem.Widget>()
        scope.launch {
            editPagesNow { it.removePage(page) }
            gone.forEach { widgets.remove(it.appWidgetId) }
        }
    }

    fun place(page: Int, item: PageItem) = editPages { it.add(page, item) }

    /** A new page holding [item]; with all pages in use nothing changes. */
    fun placeOnNewPage(item: PageItem) = editPages { p -> p.addPage().let { n -> if (n === p) p else n.add(n.count - 1, item) } }

    fun unplace(page: Int, item: PageItem) {
        scope.launch {
            editPagesNow { it.remove(page, item.id) }
            if (item is PageItem.Widget) widgets.remove(item.appWidgetId)
        }
    }

    fun moveItem(from: Int, id: String, to: Int, before: String?) = editPages { it.moveBefore(from, id, to, before) }
    fun stepItem(page: Int, id: String, delta: Int) = editPages { it.step(page, id, delta) }
    fun resizePageWidget(id: Int, rows: Int) = editPages { it.setRows(id, rows) }

    /** One step per atomic write: the widget leaves one list and joins the other in the same edit. */
    fun widgetToPage(id: Int, page: Int) { scope.launch { pageStore.updateBoard { it.moveWidgetToPage(id, page) } } }
    fun widgetToMain(id: Int) { scope.launch { pageStore.updateBoard { it.moveWidgetToMain(id) } } }

    private fun placeWidget(page: Int, id: Int, rows: Int) {
        scope.launch {
            editPagesNow { it.add(page, PageItem.Widget(id, rows)) }
            // The page may be gone or full by now; a widget no list holds is not kept in the host.
            if (id !in pageStore.board.first().widgetIds) widgets.remove(id)
        }
    }

    private fun editPages(f: (Pages) -> Pages) { scope.launch { editPagesNow(f) } }

    private suspend fun editPagesNow(f: (Pages) -> Pages) {
        var before = Pages()
        var after = Pages()
        pageStore.update { before = it; f(it).also { next -> after = next } }
        syncShortcuts(before, after)
    }

    private suspend fun syncShortcuts(before: Pages, after: Pages) {
        val touched = (before.pages.flatten() + after.pages.flatten())
            .filterIsInstance<PageItem.Shortcut>()
            .map { it.pkg to it.user }
            .toSet()
        for ((pkg, user) in touched) {
            val now = after.shortcutIds(pkg, user)
            if (now != before.shortcutIds(pkg, user)) pinShortcuts(pkg, user, now)
        }
    }

    private fun change(f: (HomeLayout) -> HomeLayout) { scope.launch { layouts.update(f) } }

    companion object {
        fun of(activity: ComponentActivity, apps: AppRepository) = HomeState(
            LayoutStore.of(activity),
            PageStore.of(activity),
            PinStore.of(activity),
            WidgetHost(activity),
            activity.lifecycleScope,
            pinShortcuts = { pkg, user, ids -> apps.pinShortcuts(pkg, user, ids) },
        )
    }
}
