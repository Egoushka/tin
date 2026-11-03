package io.github.egoushka.tin.home

import android.Manifest
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import io.github.egoushka.tin.home.signals.AlarmSource
import io.github.egoushka.tin.home.signals.CalendarSource
import io.github.egoushka.tin.home.signals.HomelabSource
import io.github.egoushka.tin.home.ui.AlarmCard
import io.github.egoushka.tin.home.ui.HomelabCard
import io.github.egoushka.tin.home.ui.TodayCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** B10 to B12: a card with nothing to show or to act on is not drawn (F6). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h914dp-420dpi")
class DeadCardsTest {
    @get:Rule val rule = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    class EmptyCalendar : android.content.ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: android.net.Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): android.database.Cursor =
            android.database.MatrixCursor(p ?: emptyArray())
        override fun getType(uri: android.net.Uri): String? = null
        override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
        override fun delete(uri: android.net.Uri, s: String?, a: Array<String>?) = 0
        override fun update(uri: android.net.Uri, values: android.content.ContentValues?, s: String?, a: Array<String>?) = 0
    }

    @After fun stop() = scope.cancel()

    private fun cards(label: Int) =
        rule.onAllNodes(hasText(context.getString(label), substring = true, ignoreCase = true)).fetchSemanticsNodes().size

    @Test fun `no Homelab card while Gatus cannot be reached`() {
        val source = HomelabSource("http://127.0.0.1:1")
        rule.setContent { HomelabCard(source) }
        source.start(scope)
        rule.waitUntil(10_000) { source.state.value.status == HomelabSource.Status.Unavailable }
        rule.waitForIdle()
        assertEquals(0, cards(R.string.homelab))
    }

    @Test fun `no Today card when nothing is left today`() {
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.READ_CALENDAR)
        org.robolectric.Robolectric.buildContentProvider(EmptyCalendar::class.java).create(android.provider.CalendarContract.AUTHORITY)
        val source = CalendarSource(context)
        rule.setContent { TodayCard(source, Actions(context)) }
        source.start(scope)
        rule.waitUntil(10_000) { source.state.value.at != 0L }
        rule.waitForIdle()
        assertEquals(true, source.state.value.granted)
        assertEquals(0, cards(R.string.today))
        assertEquals(0, cards(R.string.today_empty))
    }

    @Test fun `a tap on the Alarm card starts the alarm list`() {
        // The next alarm comes from the system; the card is drawn only when one is set.
        val fire = android.app.PendingIntent.getBroadcast(context, 0, android.content.Intent("alarm.test"), android.app.PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(android.app.AlarmManager::class.java)
            .setAlarmClock(android.app.AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 3_600_000L, null), fire)
        val set = AlarmSource(context)
        rule.setContent { AlarmCard(set, Actions(context)) }
        rule.onNode(hasText(context.getString(R.string.alarm), ignoreCase = true)).performClick()
        assertEquals(android.provider.AlarmClock.ACTION_SHOW_ALARMS, shadowOf(context as android.app.Application).nextStartedActivity?.action)
    }
}
