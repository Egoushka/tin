package io.github.egoushka.tin.fixture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** POST shows one clearable, non-ongoing notification (the kind Home counts as unread); CANCEL removes it. */
class PostReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        when (intent.action) {
            POST -> {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Fixture", NotificationManager.IMPORTANCE_LOW))
                manager.notify(
                    ID,
                    Notification.Builder(context, CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("Tin Fixture")
                        .setContentText("One unread")
                        .build(),
                )
            }
            CANCEL -> manager.cancel(ID)
        }
    }

    private companion object {
        const val POST = "io.github.egoushka.tin.fixture.POST"
        const val CANCEL = "io.github.egoushka.tin.fixture.CANCEL"
        const val CHANNEL = "fixture"
        const val ID = 1
    }
}
