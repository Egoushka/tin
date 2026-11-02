package io.github.egoushka.tin.home.signals

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Today's remaining events from CalendarContract; re-read on a calendar change and every [INTERVAL_MS]. */
class CalendarSource(private val context: Context) {
    data class State(val events: List<Event> = emptyList(), val granted: Boolean = false, val at: Long = 0L)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var scope: CoroutineScope? = null
    private var observing = false

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    fun start(scope: CoroutineScope) {
        if (this.scope != null) return
        this.scope = scope
        scope.launch {
            while (true) {
                load()
                delay(INTERVAL_MS)
            }
        }
    }

    /** Called when the permission result arrives or the calendar changes. */
    fun refresh() {
        scope?.launch { load() }
    }

    private suspend fun load() {
        val granted = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        val now = System.currentTimeMillis()
        if (!granted) {
            _state.value = State(granted = false, at = now)
            return
        }
        observe()
        val events = withContext(Dispatchers.IO) { query(now) } ?: return // keep the last good rows on a failed read
        _state.value = State(events, true, now)
    }

    /** Registering needs the permission (a SecurityException would crash Home at launch), so it waits for the grant. */
    private fun observe() {
        if (observing) return
        context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        observing = true
    }

    private fun query(now: Long): List<Event>? {
        val endOfDay = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now)
            ContentUris.appendId(it, endOfDay)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        return try {
            context.contentResolver.query(uri, projection, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        if (c.getInt(4) == 1) continue // all-day: no time to act on
                        if (c.getInt(6) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                        add(Event(c.getLong(0), c.getString(1).orEmpty(), c.getLong(2), c.getLong(3), c.getString(5)?.takeIf { it.isNotBlank() }))
                    }
                }
            }
        } catch (_: SecurityException) {
            null
        }
    }

    companion object {
        const val INTERVAL_MS = 5 * 60_000L
    }
}
