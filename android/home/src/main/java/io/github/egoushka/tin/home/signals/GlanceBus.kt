package io.github.egoushka.tin.home.signals

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the listener service knows, shared with the UI in the same process. */
object GlanceBus {
    data class State(val glance: Glance = Glance.Empty, val at: Long = 0L)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** True while the system has the listener bound; false when access is not granted or the service was unbound. */
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    fun publish(glance: Glance, at: Long) { _state.value = State(glance, at) }
    fun connected(value: Boolean) { _connected.value = value }
}
