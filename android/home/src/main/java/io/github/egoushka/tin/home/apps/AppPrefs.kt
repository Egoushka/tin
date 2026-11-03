package io.github.egoushka.tin.home.apps

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.egoushka.tin.home.tune.tuneStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The hidden app keys and the stored dock ([DockSlots.encode]; null = never changed), read together. */
data class Saved(val hidden: Set<String> = emptySet(), val dock: String? = null)

/** Two more keys in the Tune file: a second DataStore on the same file would throw. */
class AppPrefs(private val context: Context) {
    private object K {
        val hidden = stringSetPreferencesKey("apps.hidden")
        val dock = stringPreferencesKey("apps.dock")
    }

    val saved: Flow<Saved> = context.tuneStore.data.map { Saved(it[K.hidden].orEmpty(), it[K.dock]) }

    suspend fun hide(key: String) {
        context.tuneStore.edit { it[K.hidden] = it[K.hidden].orEmpty() + key }
    }

    suspend fun show(key: String) {
        context.tuneStore.edit { it[K.hidden] = it[K.hidden].orEmpty() - key }
    }

    suspend fun saveDock(slots: DockSlots) {
        context.tuneStore.edit { it[K.dock] = slots.encode() }
    }
}
