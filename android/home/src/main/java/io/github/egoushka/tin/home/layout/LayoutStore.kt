package io.github.egoushka.tin.home.layout

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.egoushka.tin.home.tune.tuneStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The card stack's order, hidden cards and widget heights, in Tune's DataStore under keys of their own. */
class LayoutStore(private val store: DataStore<Preferences>) {
    val layout: Flow<HomeLayout> = store.data.map { read(it) }.distinctUntilChanged()

    /** Reads, changes and writes in one DataStore transaction, so two updates never lose each other's change. */
    suspend fun update(change: (HomeLayout) -> HomeLayout) {
        store.edit { p -> write(p, change(read(p))) }
    }

    companion object {
        private val order = stringPreferencesKey("home.order")
        private val hidden = stringPreferencesKey("home.hidden")
        private val rows = stringPreferencesKey("home.widgetRows")

        /** For a store that changes the layout together with another key in one transaction. */
        internal fun read(p: Preferences) = HomeLayout.decode(p[order], p[hidden], p[rows])

        internal fun write(p: MutablePreferences, layout: HomeLayout) {
            p[order] = layout.encodeOrder()
            p[hidden] = layout.encodeHidden()
            p[rows] = layout.encodeRows()
        }

        fun of(context: Context) = LayoutStore(context.applicationContext.tuneStore)
    }
}
