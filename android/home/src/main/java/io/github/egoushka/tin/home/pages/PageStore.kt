package io.github.egoushka.tin.home.pages

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.egoushka.tin.home.layout.HomeLayout
import io.github.egoushka.tin.home.layout.LayoutStore
import io.github.egoushka.tin.home.tune.tuneStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The main page's layout and the pages beside it: a widget lives in exactly one of the two. */
data class Board(val layout: HomeLayout = HomeLayout(), val pages: Pages = Pages()) {
    val widgetIds: Set<Int> get() = layout.widgetIds + pages.widgetIds

    /** Takes the widget off the main stack and puts it, with its size, at the end of [page]; nothing changes if it can't go. */
    fun moveWidgetToPage(appWidgetId: Int, page: Int): Board {
        if (appWidgetId !in layout.widgetIds) return this
        val rows = layout.rows[appWidgetId] ?: 1
        val next = pages.add(page, PageItem.Widget(appWidgetId, rows))
        return if (next === pages) this else Board(layout.removeWidget(appWidgetId), next)
    }

    /** The reverse: from a page to the end of the main stack. */
    fun moveWidgetToMain(appWidgetId: Int): Board {
        val id = PageItem.Widget(appWidgetId, 1).id
        val page = pages.pageOf(id) ?: return this
        val rows = pages.pages[page].filterIsInstance<PageItem.Widget>().first { it.appWidgetId == appWidgetId }.rows
        return Board(layout.addWidget(appWidgetId, rows), pages.remove(page, id))
    }

    /** Drops widgets the host no longer knows, from both. */
    fun reconcile(live: Set<Int>): Board = Board(layout.reconcile(live), pages.reconcile(live))
}

/** The extra pages, in Tune's DataStore under a key of their own (`home.pages`). */
class PageStore(private val store: DataStore<Preferences>) {
    private val key = stringPreferencesKey("home.pages")

    val pages: Flow<Pages> = store.data.map { Pages.decode(it[key]) }.distinctUntilChanged()

    /** Both halves from one snapshot, so a widget in the middle of a move is never seen on both or neither. */
    val board: Flow<Board> = store.data.map { read(it) }.distinctUntilChanged()

    suspend fun update(change: (Pages) -> Pages) {
        store.edit { p -> p[key] = change(Pages.decode(p[key])).encode() }
    }

    /** Changes the layout and the pages in one DataStore transaction; a crash between the two writes can't happen. */
    suspend fun updateBoard(change: (Board) -> Board) {
        store.edit { p ->
            val next = change(read(p))
            LayoutStore.write(p, next.layout)
            p[key] = next.pages.encode()
        }
    }

    private fun read(p: Preferences) = Board(LayoutStore.read(p), Pages.decode(p[key]))

    companion object {
        fun of(context: Context) = PageStore(context.applicationContext.tuneStore)
    }
}
