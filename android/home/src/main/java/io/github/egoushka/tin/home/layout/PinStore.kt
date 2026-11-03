package io.github.egoushka.tin.home.layout

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.egoushka.tin.home.apps.AppEntry
import io.github.egoushka.tin.home.tune.tuneStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The apps pinned above the dock, as [AppEntry.key]s in pin order. */
class PinStore(private val store: DataStore<Preferences>) {
    private val pins = stringPreferencesKey("home.pins")

    val keys: Flow<List<String>> = store.data.map { decode(it[pins]) }.distinctUntilChanged()

    suspend fun pin(key: String) = change { if (key in it || it.size >= MAX) it else it + key }

    suspend fun unpin(key: String) = change { it - key }

    private suspend fun change(next: (List<String>) -> List<String>) {
        store.edit { p -> p[pins] = next(decode(p[pins])).joinToString(SEP) }
    }

    companion object {
        /** A starting value: one row that scrolls sideways. */
        const val MAX = 12
        private const val SEP = ","

        private fun decode(raw: String?) = raw.orEmpty().split(SEP).filter { it.isNotBlank() }.distinct()

        fun of(context: Context) = PinStore(context.applicationContext.tuneStore)

        /** Pins resolved against what is installed, in pin order; a key with no app is skipped, not forgotten. */
        fun resolve(keys: List<String>, apps: List<AppEntry>): List<AppEntry> {
            val byKey = apps.associateBy { it.key }
            return keys.mapNotNull(byKey::get)
        }
    }
}

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** For other screens (the app menu's "Pin to Home"): needs no activity and outlives the caller. */
fun pinToHome(context: Context, app: AppEntry) {
    val store = PinStore.of(context)
    scope.launch { store.pin(app.key) }
}

fun unpinFromHome(context: Context, app: AppEntry) {
    val store = PinStore.of(context)
    scope.launch { store.unpin(app.key) }
}
