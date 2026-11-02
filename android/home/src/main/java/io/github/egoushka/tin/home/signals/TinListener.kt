package io.github.egoushka.tin.home.signals

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Feeds the notification glance, and its grant is what lets Home read active media sessions
 * (MediaSessionManager.getActiveSessions needs an enabled listener).
 */
class TinListener : NotificationListenerService() {
    override fun onListenerConnected() {
        GlanceBus.connected(true)
        refresh()
    }

    override fun onListenerDisconnected() {
        GlanceBus.connected(false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Log.d(TAG, "posted ${sbn.packageName} t=${System.currentTimeMillis()}")
        refresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) = refresh()

    private fun refresh() {
        val notes = runCatching { activeNotifications }.getOrNull().orEmpty().mapNotNull { toNote(it) }
        GlanceBus.publish(Glance.of(notes), System.currentTimeMillis())
    }

    private fun toNote(sbn: StatusBarNotification): Note? {
        val n = sbn.notification
        // Unread means a person could dismiss it: no ongoing jobs, no group summaries, no transport controls, not Home itself.
        if (sbn.packageName == packageName || !sbn.isClearable || sbn.isOngoing) return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || n.category == Notification.CATEGORY_TRANSPORT) return null
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT) ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
            ?.toString().orEmpty()
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val kind = when (n.category) {
            Notification.CATEGORY_MESSAGE -> NoteKind.Message
            Notification.CATEGORY_EMAIL -> NoteKind.Mail
            else -> NoteKind.Other
        }
        return Note(sbn.packageName, app, title, text, sbn.postTime, kind)
    }

    private val StatusBarNotification.isOngoing: Boolean get() = notification.flags and Notification.FLAG_ONGOING_EVENT != 0

    companion object { const val TAG = "TinGlance" }
}
