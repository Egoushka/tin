package io.github.egoushka.tin.home.signals

import io.github.egoushka.tin.home.layer.Freshness
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Open-Meteo for Kyiv, keyless. A failed fetch keeps the last good value and does not move [State.at], so
 * [Freshness.isStale] fades the line after 3 missed refreshes; with no good value yet it says unavailable, never a number.
 */
class WeatherSource(
    private val url: String = Weather.url(),
    private val read: (String) -> String = ::httpGet,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Status { Loading, Unavailable, Ok }

    data class State(val weather: Weather? = null, val status: Status = Status.Loading, val at: Long = 0L) {
        fun after(fetched: Weather?, now: Long): State = when {
            fetched != null -> State(fetched, Status.Ok, now)
            weather != null -> this
            else -> copy(status = Status.Unavailable)
        }

        /** Three missed refreshes (Light.md); a source that never answered has no value to fade. */
        fun isStale(now: Long): Boolean = status == Status.Ok && Freshness.isStale(now, at, INTERVAL_MS)

        /** A value is quiet while it is current and names its age from the first missed refresh on. */
        fun showsAge(now: Long): Boolean = status == Status.Ok && now - at >= INTERVAL_MS
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val gate = Mutex()
    private var scope: CoroutineScope? = null
    private var tried = 0L
    private var wait = INTERVAL_MS

    fun start(scope: CoroutineScope) {
        if (this.scope != null) return
        this.scope = scope
        scope.launch {
            while (true) {
                load(force = true)
                delay(wait)
            }
        }
    }

    /** Home's minute tick: `delay` does not count the time the CPU sleeps, so the loop can lag after a night idle. */
    fun refreshIfDue() {
        scope?.launch { load(force = false) }
    }

    internal suspend fun load(force: Boolean) = gate.withLock {
        val started = clock()
        if (!force && started - tried < wait) return@withLock
        tried = started
        val fetched = withContext(Dispatchers.IO) { fetch() }
        _state.value = _state.value.after(fetched, clock())
        wait = if (fetched != null) INTERVAL_MS else RETRY_MS
    }

    // Offline, DNS, a timeout, a revoked network permission (SecurityException) and a bad body all mean "no weather now".
    private fun fetch(): Weather? = try {
        Weather.parse(read(url))
    } catch (_: Exception) {
        null
    }

    companion object {
        const val INTERVAL_MS = 15 * 60_000L // Open-Meteo's own data step is 900 s
        const val RETRY_MS = 2 * 60_000L // starting value, not calibrated: Home starts before Wi-Fi is up
    }
}

private const val TIMEOUT_MS = 5_000

private fun httpGet(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
        return c.inputStream.bufferedReader().use { it.readText() }
    } finally {
        c.disconnect()
    }
}
