package io.github.egoushka.tin.home.signals

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import io.github.egoushka.tin.home.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The approvals app's read-only provider (`content://<authority>/items|counts`). It pushes through notifyChange; the
 * poll is a heartbeat so a dead provider shows as stale instead of looking current.
 */
class NeedsYouSource(private val context: Context, private val authority: String = BuildConfig.NEEDS_YOU_AUTHORITY) {
    enum class Status { Loading, Unavailable, Ok }

    data class State(val needs: NeedsYou = NeedsYou.Empty, val status: Status = Status.Loading, val at: Long = 0L)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var scope: CoroutineScope? = null
    private val base = Uri.parse("content://$authority")

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            scope?.launch { load() }
        }
    }

    fun start(scope: CoroutineScope) {
        if (this.scope != null) return
        this.scope = scope
        try {
            context.contentResolver.registerContentObserver(base, true, observer)
        } catch (_: SecurityException) {
            // Not granted yet (a signature permission): the heartbeat keeps trying.
        }
        scope.launch {
            while (true) {
                load()
                delay(INTERVAL_MS)
            }
        }
    }

    private suspend fun load() {
        val now = System.currentTimeMillis()
        val read = withContext(Dispatchers.IO) { read() }
        _state.value = when {
            read != null -> State(read, Status.Ok, now)
            _state.value.status == Status.Ok -> _state.value // keep the last good read; its age keeps growing
            else -> State(status = Status.Unavailable, at = 0L)
        }
    }

    private fun read(): NeedsYou? = try {
        val resolver = context.contentResolver
        val counts = resolver.query(base.buildUpon().appendPath("counts").build(), null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) null else c.getInt(c.getColumnIndexOrThrow("open")) to c.getInt(c.getColumnIndexOrThrow("held"))
        }
        val items = resolver.query(base.buildUpon().appendPath("items").build(), null, null, null, null)?.use { c ->
            val id = c.getColumnIndexOrThrow("id")
            val title = c.getColumnIndexOrThrow("title")
            val source = c.getColumnIndexOrThrow("source")
            val created = c.getColumnIndexOrThrow("created_at")
            val held = c.getColumnIndexOrThrow("held")
            val link = c.getColumnIndexOrThrow("deep_link")
            buildList {
                while (c.moveToNext()) add(NeedsItem(c.getString(id), c.getString(title).orEmpty(), c.getString(source).orEmpty(), c.getLong(created), c.getInt(held) != 0, c.getString(link).orEmpty()))
            }
        }
        if (counts == null || items == null) null else NeedsYou.of(items, counts.first, counts.second)
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null // a column the provider does not have yet
    }

    companion object {
        /** A starting value: stale after 3 missed beats, so a provider that went quiet fades within 3 minutes. */
        const val INTERVAL_MS = 60_000L
    }
}
