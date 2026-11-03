package io.github.egoushka.tin.home.apps

import io.github.egoushka.tin.home.search.Fold
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One launchable activity in one profile. Plain data (no ComponentName or UserHandle) so the model runs in unit
 * tests; [user] is the profile's serial number, stable for as long as the profile exists. [rev] counts the package's
 * change events, so a redrawn icon is a new value and not a stale cache hit.
 */
data class AppEntry(val label: String, val pkg: String, val cls: String, val user: Long, val rev: Int = 0) {
    val key: String get() = "$pkg/$cls#$user"
}

/** Where apps come from; the system one is [SystemApps]. */
interface AppSource {
    /** Every launchable activity in every profile, Home itself excluded. */
    fun load(): List<AppEntry>

    /**
     * Registers on collect and unregisters on cancel. Emits once after registering, so the first load needs no
     * path of its own, then the package names of every install, removal or change (empty when only "reload" is known).
     */
    fun changes(): Flow<Set<String>>
}

/** Every launchable app A–Z and the hidden keys; [visible] is what the drawer and search show. [loaded] once the first load is in. */
data class Apps(val all: List<AppEntry> = emptyList(), val hidden: Set<String> = emptySet(), val loaded: Boolean = false) {
    val visible: List<AppEntry> = all.filter { it.key !in hidden }
    val hiddenApps: List<AppEntry> = all.filter { it.key in hidden }
    private val byKey: Map<String, AppEntry> = all.associateBy { it.key }

    operator fun get(key: String?): AppEntry? = key?.let(byKey::get)
}

/** Turns [AppSource] events and the hidden set into the model, one reload per burst of events. */
class AppList(
    private val source: AppSource,
    private val hidden: Flow<Set<String>>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(Apps())
    val state: StateFlow<Apps> = _state

    private val _visible = MutableStateFlow<List<AppEntry>>(emptyList())
    val visible: StateFlow<List<AppEntry>> = _visible

    private val revs = HashMap<String, Int>()

    private fun publish(next: Apps) {
        _state.value = next
        _visible.value = next.visible
    }

    /** Runs until cancelled. All state is written from the caller's context; only [AppSource.load] moves to [io]. */
    suspend fun keep() = coroutineScope {
        // The hidden set is read first, so the first list never shows an app that was hidden.
        val ready = CompletableDeferred<Unit>()
        launch {
            hidden.collect { h ->
                publish(_state.value.copy(hidden = h))
                ready.complete(Unit)
            }
        }
        ready.await()

        val events = Channel<Set<String>>(Channel.UNLIMITED)
        launch { source.changes().collect { events.trySend(it) } }
        for (first in events) {
            var changed = first
            while (true) changed = changed + (events.tryReceive().getOrNull() ?: break)
            val loaded = withContext(io) { source.load() }
            changed.forEach { revs[it] = (revs[it] ?: 0) + 1 }
            val all = loaded
                .map { app -> revs[app.pkg]?.let { app.copy(rev = it) } ?: app }
                .sortedBy { Fold.of(it.label) }
            publish(_state.value.copy(all = all, loaded = true))
        }
    }
}
