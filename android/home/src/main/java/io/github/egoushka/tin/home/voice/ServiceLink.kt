package io.github.egoushka.tin.home.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.egoushka.tin.voice.IVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Binds tin's VoiceService, which only apps signed with tin's key may do (permission `...permission.VOICE`). When tin
 * is not installed, is not visible, or signs differently, [bind] ends with no proxy and [voice] stays null.
 * Called on the main thread, like the ServiceConnection callbacks.
 */
class ServiceLink(private val context: Context) : VoiceLink {
    @Volatile private var proxy: IVoice? = null
    private var wanted = false
    private var bound = false

    override var onLost: () -> Unit = {}

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            proxy = IVoice.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(name: ComponentName) = lose()

        // tin was updated or removed: the binding is dead for good, so bind again if Home is still in front.
        override fun onBindingDied(name: ComponentName) {
            lose()
            release()
            if (wanted) connect()
        }

        override fun onNullBinding(name: ComponentName) = release()
    }

    override fun voice(): IVoice? = proxy

    override fun bind() {
        wanted = true
        if (!bound) connect()
    }

    override fun unbind() {
        wanted = false
        proxy = null
        release()
    }

    private fun connect() {
        val intent = Intent().setComponent(SERVICE)
        bound = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            false
        }
        if (!bound) release()
    }

    private fun release() {
        try {
            context.unbindService(connection)
        } catch (e: IllegalArgumentException) {
            // Not registered: the bind never took.
        }
        bound = false
    }

    private fun lose() {
        proxy = null
        onLost()
    }

    private companion object {
        val SERVICE = ComponentName("io.github.egoushka.tin", "io.github.egoushka.tin.VoiceService")
    }
}

private val Context.voiceStore: DataStore<Preferences> by preferencesDataStore(name = "tin.voice")

/** `tin.voice` / `uses`: how often voice returned text, for the 7-day trial. A number; the text never gets here. */
class DataStoreUses(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : VoiceUses {
    override fun bump() {
        scope.launch {
            try {
                context.voiceStore.edit { it[USES] = (it[USES] ?: 0) + 1 }
            } catch (e: IOException) {
                // A missed count must not fail a voice command.
            }
        }
    }

    private companion object {
        val USES = intPreferencesKey("uses")
    }
}
