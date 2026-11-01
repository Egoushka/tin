package io.github.egoushka.tin

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.IBinder
import android.util.Log
import io.github.egoushka.tin.speech.AudioSource
import io.github.egoushka.tin.speech.Listener
import io.github.egoushka.tin.speech.ModelStore
import io.github.egoushka.tin.speech.Models
import io.github.egoushka.tin.speech.PushToTalk
import io.github.egoushka.tin.speech.SAMPLE_RATE
import io.github.egoushka.tin.speech.SherpaRecognizer
import io.github.egoushka.tin.speech.SherpaVad
import io.github.egoushka.tin.speech.SpeechServer
import io.github.egoushka.tin.speech.Transcripts
import java.io.File
import kotlin.concurrent.thread

/**
 * Owns the mic and the speech model in the app process. Android lets a microphone foreground service start only
 * while the app is visible, so MainActivity starts it; after that it records for tin's MCP tools and for
 * push-to-talk (notification action and Quick Settings tile) with the screen off.
 */
class SpeechService : Service() {
    private val store by lazy { ModelStore(File(filesDir, "models")) }
    private val transcripts = Transcripts()
    @Volatile private var listener: Listener? = null
    @Volatile var state: String = "loading model"
        private set
    private val ptt = PushToTalk()
    private val server by lazy {
        SpeechServer({ listener }, { MicSource() }, File(filesDir, "bench").apply { mkdirs() }, transcripts)
    }

    private val binder = object : ISpeech.Stub() {
        override fun handle(line: String): String {
            // Only tin's own shell-uid process holds this binder, but check anyway: a mic is not for any caller.
            val uid = Binder.getCallingUid()
            if (uid != 0 && uid != SHELL_UID && uid != android.os.Process.myUid()) {
                return """{"ok":false,"error":"caller uid $uid refused"}"""
            }
            return server.handle(line)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Speech", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        thread(name = "speech-load") { load() }
    }

    private fun load() {
        val problems = store.verify(Models.PARAKEET_V3_INT8) + store.verify(Models.SILERO_VAD)
        if (problems.isNotEmpty()) {
            update("model missing: ${problems.joinToString("; ")}. Run scripts/speech-push-model.sh")
            return
        }
        try {
            val start = System.nanoTime()
            listener = Listener(SherpaVad(store), SherpaRecognizer(store))
            update("ready (model loaded in ${(System.nanoTime() - start) / 1_000_000} ms)")
        } catch (e: Throwable) {
            Log.e(TAG, "model load failed", e)
            update("model load failed: $e")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE) toggle()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    /** Push-to-talk: the first toggle records, the second decodes what was said into [transcripts]. */
    fun toggle() {
        when (ptt.state) {
            PushToTalk.State.Idle -> {
                if (listener == null) return update(state)
                if (!server.micBusy.compareAndSet(false, true)) return update("mic busy")
                recordStartedAt = System.currentTimeMillis()
                ptt.press()
                update("recording; tap again to stop")
                thread(name = "ptt-record") { record() }
            }
            PushToTalk.State.Recording -> ptt.release()?.let(::decode) ?: update("ready (too short, dropped)")
            PushToTalk.State.Decoding -> Unit
        }
    }

    private fun record() {
        try {
            MicSource().use { mic ->
                val buffer = FloatArray(1600)
                while (ptt.state == PushToTalk.State.Recording) {
                    val n = mic.read(buffer)
                    if (n <= 0 || !ptt.append(buffer, n)) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "push-to-talk mic failed", e)
        } finally {
            server.micBusy.set(false)
        }
        // Hit the length cap or the mic failed: stop as if released.
        if (ptt.state == PushToTalk.State.Recording) ptt.release()?.let(::decode) ?: update("ready")
    }

    @Volatile private var recordStartedAt = 0L

    private fun decode(audio: FloatArray) {
        update("decoding")
        thread(name = "ptt-decode") {
            try {
                val heard = listener!!.transcript(audio)
                val t = transcripts.add(recordStartedAt, heard)
                update("heard #${t.id}: ${heard.text}")
            } catch (e: Exception) {
                update("decode failed: $e")
            } finally {
                ptt.decoded()
            }
        }
    }

    private fun update(text: String) {
        state = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
        PushToTalkTile.refresh(this)
    }

    private fun notification(): Notification {
        val toggle = PendingIntent.getService(
            this, 0, Intent(this, SpeechService::class.java).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE,
        )
        val label = if (ptt.state == PushToTalk.State.Recording) "Stop" else "Talk"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("tin speech")
            .setContentText(state)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, label, toggle).build())
            .build()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** 16 kHz mono float from the voice-recognition source; started on creation, released on close. */
    @SuppressLint("MissingPermission") // MainActivity starts this service only after RECORD_AUDIO is granted.
    private class MicSource : AudioSource, AutoCloseable {
        private val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT), SAMPLE_RATE * 4),
        ).also {
            check(it.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialise" }
            it.startRecording()
        }

        override fun read(buffer: FloatArray): Int =
            record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING).let { if (it < 0) -1 else it }

        override fun close() {
            record.stop()
            record.release()
        }
    }

    companion object {
        const val ACTION_TOGGLE = "io.github.egoushka.tin.PTT_TOGGLE"
        const val PERMISSION = Manifest.permission.RECORD_AUDIO
        private const val TAG = "tin-speech"
        private const val CHANNEL = "speech"
        private const val NOTIFICATION = 1
        private const val SHELL_UID = 2000

        @Volatile var instance: SpeechService? = null
            private set
    }
}
