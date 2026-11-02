package io.github.egoushka.tin.home

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import io.github.egoushka.tin.home.signals.TinListener

/** A dock slot: a Lucide icon and the default handler for one job, so the dock follows the user's app choices. */
enum class Dock(val icon: String, @StringRes val label: Int, val intent: () -> Intent, val fallback: (() -> Intent)? = null) {
    Phone("phone", R.string.dock_phone, { Intent(Intent.ACTION_DIAL) }),
    Messages("msg", R.string.dock_messages, { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING) }),
    Camera("camera", R.string.dock_camera, { Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) }),
    Browser("globe", R.string.dock_browser, { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER) }),
    Maps("map", R.string.dock_maps, { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS) },
        // GrapheneOS ships no maps app; the web map keeps the slot alive until one is installed.
        { Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/")) }),
}

class Actions(private val context: Context) {
    fun start(intent: Intent, what: String) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.open_failed, what), Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, context.getString(R.string.open_failed, what), Toast.LENGTH_SHORT).show()
        }
    }

    fun dock(d: Dock) {
        val intent = d.intent().takeIf { it.resolveActivity(context.packageManager) != null } ?: d.fallback?.invoke() ?: d.intent()
        start(intent, context.getString(d.label))
    }

    /** The grant that lets the glance and the music card read notifications and media sessions. */
    fun openListenerSettings() = start(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, TinListener::class.java).flattenToString(),
        ),
        context.getString(R.string.notif_access),
    )

    fun webSearch(query: String) = start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query), query)

    /** Until the speech slice lands, the mic hands over to the system voice assistant; false when there is none (stock GrapheneOS). */
    fun voice(): Boolean {
        val intent = Intent(Intent.ACTION_VOICE_COMMAND)
        if (intent.resolveActivity(context.packageManager) == null) return false
        start(intent, context.getString(R.string.mic))
        return true
    }

    /** StatusBarManager.expandNotificationsPanel is hidden API; launchers have called it this way for years. */
    @SuppressLint("WrongConstant")
    fun openNotifications() {
        runCatching {
            val sb = context.getSystemService("statusbar")
            sb.javaClass.getMethod("expandNotificationsPanel").invoke(sb)
        }
    }

    private val cameras = context.getSystemService(CameraManager::class.java)
    private var torchOn = false
    private val torchId: String? by lazy {
        cameras.cameraIdList.firstOrNull {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }

    init {
        cameras.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == torchId) torchOn = enabled
            }
        }, null)
    }

    fun toggleTorch() {
        val id = torchId ?: return
        runCatching { cameras.setTorchMode(id, !torchOn) }
    }
}
