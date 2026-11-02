package io.github.egoushka.tin.home.signals

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Everything Home's layer reads: one per process, so a recreated activity registers no second observer or receiver. */
class Signals private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val music = MusicSource(app)
    val calendar = CalendarSource(app)
    val alarm = AlarmSource(app)
    val glance: StateFlow<GlanceBus.State> = GlanceBus.state
    val listenerConnected: StateFlow<Boolean> = GlanceBus.connected

    private fun start() {
        calendar.start(scope)
        alarm.start()
        scope.launch {
            GlanceBus.connected.collect { if (it) music.start() else music.stop() }
        }
    }

    companion object {
        @Volatile private var instance: Signals? = null

        fun of(context: Context): Signals = instance ?: synchronized(this) {
            instance ?: Signals(context).also { it.start(); instance = it }
        }
    }
}
