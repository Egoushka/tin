package io.github.egoushka.tin.home.signals

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Everything Home's layer reads, built once per process and shared by the activity. */
class Signals(context: Context) {
    private val app = context.applicationContext
    val music = MusicSource(app)
    val glance: StateFlow<GlanceBus.State> = GlanceBus.state
    val listenerConnected: StateFlow<Boolean> = GlanceBus.connected

    fun start(scope: CoroutineScope) {
        scope.launch {
            GlanceBus.connected.collect { if (it) music.start() else music.stop() }
        }
    }
}
