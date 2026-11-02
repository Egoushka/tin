package io.github.egoushka.tin

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.widget.TextView
import rikka.shizuku.Shizuku

/** Asks Shizuku for permission once, then binds the user service that keeps `tin serve` running. */
class MainActivity : Activity() {
    private lateinit var text: TextView

    private val args = Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, TinService::class.java.name))
        .daemon(true)
        .processNameSuffix("tin")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = ITinService.Stub.asInterface(binder)
            tin = service
            handSpeech()
            // The service starts tin on a background thread; ask again shortly so the pid is filled in.
            text.postDelayed({ show("bridge running: ${runCatching { service.status() }.getOrElse { it.toString() }}") }, 1_000)
        }

        override fun onServiceDisconnected(name: ComponentName) = show("bridge service stopped")
    }

    @Volatile private var tin: ITinService? = null
    @Volatile private var speech: IBinder? = null

    // SpeechService is in this process, so its ISpeech binder is a local object that Binder can pass to TinService.
    private val speechConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            speech = binder
            handSpeech()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            speech = null
        }
    }

    private fun handSpeech() {
        val t = tin ?: return
        val s = speech ?: return
        runCatching { t.setSpeech(s) }.onFailure { show("speech hand-over failed: $it") }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) bind() else show("Shizuku permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { setPadding(48, 96, 48, 48); textSize = 16f }
        setContentView(text)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        startSpeech()
        when {
            !Shizuku.pingBinder() -> show("Shizuku is not running. Start it, then reopen tin.")
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> bind()
            else -> Shizuku.requestPermission(REQUEST_CODE)
        }
    }

    /** Speech needs the mic and notification permissions; the service has to start while this screen is visible. */
    private fun startSpeech() {
        val missing = arrayOf(SpeechService.PERMISSION, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) return requestPermissions(missing.toTypedArray(), SPEECH_REQUEST)
        startForegroundService(Intent(this, SpeechService::class.java))
        bindService(Intent(this, SpeechService::class.java), speechConnection, BIND_AUTO_CREATE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == SPEECH_REQUEST && checkSelfPermission(SpeechService.PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            startSpeech()
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        if (speech != null) unbindService(speechConnection)
        super.onDestroy()
    }

    // daemon(true) keeps the service alive after this activity and its process are gone.
    private fun bind() {
        show("starting bridge…")
        Shizuku.bindUserService(args, connection)
    }

    private fun show(message: String) = runOnUiThread { text.text = message }

    private companion object {
        const val REQUEST_CODE = 1
        const val SPEECH_REQUEST = 2
    }
}
