package io.github.egoushka.tin.home.layout

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.egoushka.tin.home.tune.tuneStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The card stack's order, hidden cards and widget heights, in Tune's DataStore under keys of their own. */
class LayoutStore(private val store: DataStore<Preferences>) {
    private object K {
        val order = stringPreferencesKey("home.order")
        val hidden = stringPreferencesKey("home.hidden")
        val rows = stringPreferencesKey("home.widgetRows")
    }

    val layout: Flow<HomeLayout> = store.data.map(::read).distinctUntilChanged()

    /** Reads, changes and writes in one DataStore transaction, so two updates never lose each other's change. */
    suspend fun update(change: (HomeLayout) -> HomeLayout) {
        store.edit { p ->
            val next = change(read(p))
            p[K.order] = next.encodeOrder()
            p[K.hidden] = next.encodeHidden()
            p[K.rows] = next.encodeRows()
        }
    }

    private fun read(p: Preferences) = HomeLayout.decode(p[K.order], p[K.hidden], p[K.rows])

    companion object {
        fun of(context: Context) = LayoutStore(context.applicationContext.tuneStore)
    }
}
