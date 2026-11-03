package io.github.egoushka.tin.home.voice

import android.content.Context
import android.os.RemoteException
import io.github.egoushka.tin.voice.IVoice
import io.github.egoushka.tin.voice.IVoiceCallback
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/** The connection to tin's VoiceService as the engine sees it; [ServiceLink] is the real one. */
interface VoiceLink {
    /** The live proxy, or null when tin is missing, not connected yet, or gone. */
    fun voice(): IVoice?
    fun bind()
    fun unbind()

    /** Set by the engine; runs when a connection that was live drops (tin was killed). */
    var onLost: () -> Unit
}

/** Counts voice commands that returned text. It takes no argument on purpose: nothing said can reach the store. */
fun interface VoiceUses {
    fun bump()
}

/**
 * Speech to text through tin: the model and the mic stay in tin's process, Home holds a Binder proxy and gets text
 * back. Neither audio nor text is logged.
 */
class TinVoiceEngine(private val link: VoiceLink, private val uses: VoiceUses) : VoiceEngine {
    private val listening = AtomicReference<CancellableContinuation<String?>?>(null)

    init {
        link.onLost = { listening.getAndSet(null)?.takeIf { it.isActive }?.resume(null) }
    }

    override fun attach() = link.bind()

    /** Home left the front: a listen in flight is cancelled in tin before the connection goes. */
    override fun detach() {
        listening.get()?.cancel()
        link.unbind()
    }

    /** False while tin is missing, not opened since boot, or its model is loading; the mic then falls back to the assistant. */
    override fun ready(): Boolean = try {
        link.voice()?.ready() == true
    } catch (e: RemoteException) {
        false
    }

    override suspend fun listen(show: (VoiceState) -> Unit): String? {
        val voice = link.voice() ?: return null
        return suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            fun finish(text: String?) {
                if (!done.compareAndSet(false, true)) return
                listening.compareAndSet(cont, null)
                if (!cont.isActive) return
                if (text != null) uses.bump()
                cont.resume(text)
            }
            // Callbacks arrive on Binder threads and may trail a cancel; once done, they change nothing.
            val callback = object : IVoiceCallback.Stub() {
                override fun onState(state: Int) {
                    if (done.get()) return
                    when (state) {
                        STATE_LISTENING -> show(VoiceState.Listening)
                        STATE_THINKING -> show(VoiceState.Thinking)
                    }
                }

                override fun onHeard(text: String?) = finish(text?.trim()?.ifEmpty { null })

                // The reason ("mic busy", "speech off") is tin's; the bar has one error word.
                override fun onError(reason: String?) = finish(null)
            }
            listening.set(cont)
            try {
                voice.listen(MAX_SECONDS, callback)
            } catch (e: RemoteException) {
                finish(null)
                return@suspendCancellableCoroutine
            }
            // After listen(), so a cancel can never reach tin before the listen it belongs to.
            cont.invokeOnCancellation {
                done.set(true)
                listening.compareAndSet(cont, null)
                try {
                    voice.cancel()
                } catch (e: RemoteException) {
                    // tin is gone; nothing is recording.
                }
            }
        }
    }

    companion object {
        private const val STATE_LISTENING = 1
        private const val STATE_THINKING = 2

        /** A command is a few words; starting value, tin ends the recording earlier when the speech stops. */
        const val MAX_SECONDS = 8.0

        @Volatile private var instance: TinVoiceEngine? = null

        fun of(context: Context): TinVoiceEngine = instance ?: synchronized(this) {
            instance ?: TinVoiceEngine(ServiceLink(context.applicationContext), DataStoreUses(context.applicationContext))
                .also { instance = it }
        }
    }
}
