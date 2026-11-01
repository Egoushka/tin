package io.github.egoushka.tin

import android.app.Activity
import android.content.ComponentName
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
            // The service starts tin on a background thread; ask again shortly so the pid is filled in.
            text.postDelayed({ show("bridge running: ${runCatching { service.status() }.getOrElse { it.toString() }}") }, 1_000)
        }

        override fun onServiceDisconnected(name: ComponentName) = show("bridge service stopped")
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) bind() else show("Shizuku permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { setPadding(48, 96, 48, 48); textSize = 16f }
        setContentView(text)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        when {
            !Shizuku.pingBinder() -> show("Shizuku is not running. Start it, then reopen tin.")
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> bind()
            else -> Shizuku.requestPermission(REQUEST_CODE)
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
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
    }
}
