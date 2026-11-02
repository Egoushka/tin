package io.github.egoushka.tin

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import io.github.egoushka.tin.voice.IVoice
import io.github.egoushka.tin.voice.IVoiceCallback
import kotlin.concurrent.thread

/**
 * Home's voice: one command through the running [SpeechService]'s model and mic. Bound by apps holding the
 * signature permission io.github.egoushka.tin.permission.VOICE. It never starts SpeechService: Android does not let
 * a microphone foreground service start from the background, so until tin is opened [IVoice.ready] is false.
 * Audio and text are never logged.
 */
class VoiceService : Service() {
    @Volatile private var cancelled = false

    private val binder = object : IVoice.Stub() {
        override fun listen(maxSeconds: Double, cb: IVoiceCallback) {
            val speech = SpeechService.instance ?: return answer { cb.onError("speech off") }
            val seconds = maxSeconds.coerceIn(1.0, MAX_SECONDS)
            cancelled = false
            thread(name = "voice-listen") { speech.voiceCommand(seconds, cb) }
        }

        override fun cancel() {
            cancelled = true
        }

        override fun ready(): Boolean = SpeechService.instance?.ready() == true
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun SpeechService.voiceCommand(seconds: Double, cb: IVoiceCallback) {
        when (val result = command(seconds, { answer { cb.onState(1) } }, { answer { cb.onState(2) } }, { cancelled })) {
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
