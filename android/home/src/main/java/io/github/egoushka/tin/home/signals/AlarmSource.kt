package io.github.egoushka.tin.home.signals

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The next alarm clock (epoch ms) or null; pushed by the system, so it never goes stale by the clock. */
class AlarmSource(private val context: Context) {
    private val manager = context.getSystemService(AlarmManager::class.java)
    private val _next = MutableStateFlow<Long?>(read())
    val next: StateFlow<Long?> = _next

    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { _next.value = read() }
    }

    fun start() {
        if (started) return
        started = true
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        _next.value = read()
    }

    private fun read(): Long? = manager.nextAlarmClock?.triggerTime
}
