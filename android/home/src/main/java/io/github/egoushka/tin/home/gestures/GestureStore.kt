package io.github.egoushka.tin.home.gestures

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.egoushka.tin.home.tune.tuneStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The gesture assignments, in Tune's DataStore under a key of their own (`home.gestures`). */
class GestureStore(private val store: DataStore<Preferences>) {
    private val key = stringPreferencesKey("home.gestures")

    val map: Flow<GestureMap> = store.data.map { GestureMap.decode(it[key]) }.distinctUntilChanged()

    suspend fun set(g: Gesture, a: GestureAction) {
        store.edit { p -> p[key] = GestureMap.decode(p[key]).with(g, a).encode() }
    }

    companion object {
        fun of(context: Context) = GestureStore(context.applicationContext.tuneStore)
    }
}
