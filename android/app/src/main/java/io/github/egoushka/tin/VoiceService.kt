package io.github.egoushka.tin

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import io.github.egoushka.tin.voice.IVoice
import io.github.egoushka.tin.voice.IVoiceCallback
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Home's voice: one command through the running [SpeechService]'s model and mic. Bound by apps holding the
 * signature permission io.github.egoushka.tin.permission.VOICE. It never starts SpeechService: Android does not let
 * a microphone foreground service start from the background, so until tin is opened [IVoice.ready] is false.
 * Audio and text are never logged.
 */
class VoiceService : Service() {
    /** The latest listen's cancel flag, read by Listener.command's `cancelled` once per chunk. */
    @Volatile private var cancelled = AtomicBoolean(false)

    private val binder = object : IVoice.Stub() {
        override fun listen(maxSeconds: Double, cb: IVoiceCallback) {
            val speech = SpeechService.instance ?: return answer { cb.onError("speech off") }
            val seconds = maxSeconds.coerceIn(1.0, MAX_SECONDS)
            val flag = AtomicBoolean(false).also { cancelled = it }
            thread(name = "voice-listen") { speech.voiceCommand(seconds, cb, flag) }
        }

        override fun cancel() {
            cancelled.set(true)
        }

        override fun ready(): Boolean = SpeechService.instance?.ready() == true
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun SpeechService.voiceCommand(seconds: Double, cb: IVoiceCallback, cancelled: AtomicBoolean) {
        when (val result = command(seconds, { answer { cb.onState(1) } }, { answer { cb.onState(2) } }, cancelled::get)) {
            is SpeechService.Command.Heard -> answer { cb.onHeard(result.text) }
            is SpeechService.Command.Failed -> answer { cb.onError(result.reason) }
        }
    }

    private companion object {
        const val MAX_SECONDS = 60.0
    }
}

/** The caller may have died; a oneway call to a dead binder is not our failure. */
private fun answer(call: () -> Unit) {
    try {
        call()
    } catch (e: RemoteException) {
        Log.w("tin-voice", "voice caller gone")
    }
}
