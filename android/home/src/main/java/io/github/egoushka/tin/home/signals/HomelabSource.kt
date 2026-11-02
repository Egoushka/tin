package io.github.egoushka.tin.home.signals

import io.github.egoushka.tin.home.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gatus on the tailnet, read-only and unauthenticated (it listens on the tailnet address only). A failed fetch keeps the
 * last good state and does not move [State.at], so [Freshness.isStale] fades the card after 3 missed polls.
 */
class HomelabSource(private val baseUrl: String = BuildConfig.GATUS_URL) {
    enum class Status { Loading, Unavailable, Ok }

    data class State(val homelab: Homelab = Homelab.Empty, val status: Status = Status.Loading, val at: Long = 0L)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var started = false

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                load()
                delay(INTERVAL_MS)
            }
        }
    }

    private suspend fun load() {
        val body = withContext(Dispatchers.IO) { fetch() }
        _state.value = if (body != null) State(body, Status.Ok, System.currentTimeMillis())
        else _state.value.let { if (it.status == Status.Ok) it else it.copy(status = Status.Unavailable) }
    }

    // pageSize 100 caps both results per endpoint and endpoints per page; 49 endpoints today (homelab-gitops FACTS.md).
    private fun fetch(): Homelab? = try {
        val c = URL("$baseUrl/api/v1/endpoints/statuses?page=1&pageSize=100").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            if (c.responseCode == 200) Homelab.parse(c.inputStream.bufferedReader().use { it.readText() }) else null
        } finally {
            c.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val INTERVAL_MS = 60_000L // starting value, not calibrated
        private const val TIMEOUT_MS = 5_000
    }
}
