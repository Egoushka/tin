package io.github.egoushka.tin.home.signals

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Everything Home's layer reads: one per process, so a recreated activity registers no second observer or receiver. */
class Signals private constructor(context: Context) {
    private val app = context.applicationContext
    // A source that throws loses its own update; it must not take Home down with it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> Log.w("Signals", "signal source failed", e) })
    val music = MusicSource(app)
    val calendar = CalendarSource(app)
    val alarm = AlarmSource(app)
    val needsYou = NeedsYouSource(app)
    val homelab = HomelabSource()
    val weather = WeatherSource()
    val glance: StateFlow<GlanceBus.State> = GlanceBus.state
    val listenerConnected: StateFlow<Boolean> = GlanceBus.connected

    private fun start() {
        calendar.start(scope)
        alarm.start()
        needsYou.start(scope)
        homelab.start(scope)
        weather.start(scope)
        scope.launch {
            GlanceBus.connected.collect { if (it) music.start() else music.stop() }
        }
    }

    companion object {
        @Volatile private var instance: Signals? = null

        /** Sources that read nothing: tests seed their states and nothing observes the device. */
        internal fun idle(context: Context): Signals = Signals(context)

        fun of(context: Context): Signals = instance ?: synchronized(this) {
            instance ?: Signals(context).also { it.start(); instance = it }
        }
    }
}
