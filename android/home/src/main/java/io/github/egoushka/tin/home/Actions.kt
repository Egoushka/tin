package io.github.egoushka.tin.home

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import io.github.egoushka.tin.home.signals.MusicState
import io.github.egoushka.tin.home.signals.OpenVia
import io.github.egoushka.tin.home.signals.TinListener
import io.github.egoushka.tin.home.signals.openFirst
import io.github.egoushka.tin.home.signals.openOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val APPROVALS_PACKAGE = "com.yehor.approvals"

/** One job of the dock and the system's default handler for it: what a slot holds until the user picks an app. */
enum class DockDefault(
    val icon: String,
    @StringRes val label: Int,
    val intent: () -> Intent,
    val fallback: (() -> Intent)? = null,
    /** What a swipe up on the empty slot opens, the way an app's first shortcut does; null when there is nothing to offer. */
    val swipe: (() -> Intent)? = null,
) {
    Phone("phone", R.string.dock_phone, { Intent(Intent.ACTION_DIAL) }),
    Messages("msg", R.string.dock_messages, { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING) }),
    Camera("camera", R.string.dock_camera, { Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) },
        swipe = { Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA) }),
    Browser("globe", R.string.dock_browser, { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER) }),
    Maps("map", R.string.dock_maps, { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS) },
        // GrapheneOS ships no maps app; the web map keeps the slot alive until one is installed.
        { Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/")) }),
}

class Actions(private val context: Context, val askCalendar: () -> Unit = {}) {
    fun start(intent: Intent, what: String) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.open_failed, what), Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, context.getString(R.string.open_failed, what), Toast.LENGTH_SHORT).show()
        }
    }

    fun dock(d: DockDefault) {
        val intent = d.intent().takeIf { it.resolveActivity(context.packageManager) != null } ?: d.fallback?.invoke() ?: d.intent()
        start(intent, context.getString(d.label))
    }

    /** The activity [d] opens now; null when nothing handles it or the system would ask which app ("android" is its chooser). */
    fun handler(d: DockDefault): Pair<String, String>? {
        val info = context.packageManager.resolveActivity(d.intent(), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: return null
        return if (info.packageName == "android") null else info.packageName to info.name
    }

    /** The grant that lets the glance and the music card read notifications and media sessions. */
    fun openListenerSettings() = start(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, TinListener::class.java).flattenToString(),
        ),
        context.getString(R.string.notif_access),
    )

    /** Opens the approvals app on its queue; the card never decides anything itself. */
    fun openApprovals() {
        val intent = context.packageManager.getLaunchIntentForPackage(APPROVALS_PACKAGE)
        if (intent == null) Toast.makeText(context, context.getString(R.string.open_failed, context.getString(R.string.needs_you)), Toast.LENGTH_SHORT).show()
        else start(intent, context.getString(R.string.needs_you))
    }

    /** Opens the app that is playing: its session's own screen, else its launcher. */
    fun openMusic(music: MusicState) {
        val name = music.app.ifBlank { context.getString(R.string.card_music) }
        val opened = openFirst(openOrder(music.sessionActivity != null, music.launchable)) { via ->
            when (via) {
                OpenVia.Session -> runCatching { music.sessionActivity?.send() }.isSuccess
                OpenVia.Launch -> context.packageManager.getLaunchIntentForPackage(music.packageName)?.also { start(it, name) } != null
            }
        }
        if (!opened) Toast.makeText(context, context.getString(R.string.open_failed, name), Toast.LENGTH_SHORT).show()
    }

    /** An item's own link (`approvals://item/<id>`), so a spoken "approve" lands on the item and waits for a tap. */
    fun openItem(deepLink: String) = start(Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)), context.getString(R.string.needs_you))

    fun openAppSettings() = start(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        context.getString(R.string.app_name),
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

    /** The quick settings panel, through the same hidden service as [openNotifications]. */
    @SuppressLint("WrongConstant")
    fun openQuickSettings() {
        runCatching {
            val sb = context.getSystemService("statusbar")
            sb.javaClass.getMethod("expandSettingsPanel").invoke(sb)
        }
    }

    private val cameras = context.getSystemService(CameraManager::class.java)
    private val torchId: String? by lazy {
        runCatching {
            cameras.cameraIdList.firstOrNull {
                cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
    }
    private val torchOn = MutableStateFlow(false)

    /** The system's torch, so a change from Quick Settings shows here too. */
    val torch: StateFlow<Boolean> = torchOn

    /** False on a phone with no flash unit; the switch is not drawn then. */
    val hasTorch: Boolean get() = torchId != null

    init {
        cameras.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == torchId) torchOn.value = enabled
            }
        }, null)
    }

    fun toggleTorch() {
        val id = torchId ?: return
        runCatching { cameras.setTorchMode(id, !torchOn.value) }
    }
}
