package io.github.egoushka.tin.home

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.egoushka.tin.home.layer.LocalNow
import io.github.egoushka.tin.home.layout.HomeFrame
import io.github.egoushka.tin.home.layout.HomeStack
import io.github.egoushka.tin.home.layout.HomeState
import io.github.egoushka.tin.home.layout.LayoutStore
import io.github.egoushka.tin.home.layout.PinStore
import io.github.egoushka.tin.home.layout.WidgetHost
import io.github.egoushka.tin.home.pages.PageStore
import io.github.egoushka.tin.home.signals.AppCount
import io.github.egoushka.tin.home.signals.CalendarSource
import io.github.egoushka.tin.home.signals.Event
import io.github.egoushka.tin.home.signals.Glance
import io.github.egoushka.tin.home.signals.GlanceBus
import io.github.egoushka.tin.home.signals.Homelab
import io.github.egoushka.tin.home.signals.HomelabSource
import io.github.egoushka.tin.home.signals.MusicState
import io.github.egoushka.tin.home.signals.NeedsItem
import io.github.egoushka.tin.home.signals.NeedsYou
import io.github.egoushka.tin.home.signals.NeedsYouSource
import io.github.egoushka.tin.home.signals.Note
import io.github.egoushka.tin.home.signals.NoteKind
import io.github.egoushka.tin.home.signals.Probe
import io.github.egoushka.tin.home.signals.Signals
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.theme.LocalTune
import io.github.egoushka.tin.home.theme.Ng
import io.github.egoushka.tin.home.tune.Tune
import io.github.egoushka.tin.home.ui.Glance
import io.github.egoushka.tin.home.ui.GlanceRoutes
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * LB8, the before half: Home's page 0 as it is today, with a fixed clock and fake signals standing for the board fixture
 * (Sunday 2026-11-15 in Kyiv). Goldens live in src/test/screenshots; `:home:verifyRoborazziDebug` compares them, and
 * `:home:recordRoborazziDebug` rewrites them after a change that is meant to move pixels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeShotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val kyiv = ZoneId.of("Europe/Kyiv")
    private val zone = TimeZone.getDefault()
    private val dir = File.createTempFile("tin-home-shots", "").apply { delete(); mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun at(day: Int, h: Int, m: Int) = LocalDateTime.of(2026, 11, day, h, m).atZone(kyiv).toInstant().toEpochMilli()

    @Before fun fixWorld() {
        TimeZone.setDefault(TimeZone.getTimeZone(kyiv))
        val resolver = rule.activity.contentResolver
        Settings.System.putString(resolver, Settings.System.TIME_12_24, "24")
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @After fun restore() {
        TimeZone.setDefault(zone)
        GlanceBus.publish(Glance.Empty, 0L)
        GlanceBus.connected(false)
        scope.cancel()
        dir.deleteRecursively()
    }

    @Config(sdk = [36], qualifiers = "w412dp-h914dp-notnight-420dpi")
    @Test fun at1012Light() = shoot(10, 12, "light")

    @Config(sdk = [36], qualifiers = "w412dp-h914dp-night-420dpi")
    @Test fun at1012Dark() = shoot(10, 12, "dark")

    @Config(sdk = [36], qualifiers = "w412dp-h914dp-notnight-420dpi")
    @Test fun at1205Light() = shoot(12, 5, "light")

    @Config(sdk = [36], qualifiers = "w412dp-h914dp-night-420dpi")
    @Test fun at1205Dark() = shoot(12, 5, "dark")

    private fun fixture(now: Long): Signals {
        val signals = Signals.idle(rule.activity.applicationContext)
        val hour = 60 * 60_000L
        signals.needsYou.seed(
            NeedsYouSource.State(
                NeedsYou.of(
                    listOf(
                        NeedsItem("1", "Review: board layout", "PR", now - 5 * hour, false, ""),
                        NeedsItem("2", "Review: sync fix", "PR", now - 4 * hour, false, ""),
                        NeedsItem("3", "Approve the deploy", "Approval", now - 3 * hour, true, ""),
                        NeedsItem("4", "Reply to Anna", "Reply", now - 2 * hour, false, ""),
                    ),
                    open = 4, held = 1,
                ),
                NeedsYouSource.Status.Ok, now,
            ),
        )
        signals.calendar.seed(
            CalendarSource.State(
                listOf(
                    Event(1, "Standup", at(15, 10, 30), at(15, 10, 45), null),
                    Event(2, "Lunch", at(15, 13, 0), at(15, 14, 0), "Podil"),
                    Event(3, "Sprint review", at(15, 15, 0), at(15, 16, 0), null),
                    Event(4, "Dentist", at(15, 15, 30), at(15, 16, 30), null),
                ),
                granted = true, at = now,
            ),
        )
        signals.homelab.seed(
            HomelabSource.State(
                Homelab.of(listOf(Probe("nytka", "hedzer", listOf(true, false, false)), Probe("gatus", "hedzer", listOf(true, true, true)))),
                HomelabSource.Status.Ok, now,
            ),
        )
        signals.music.seed(MusicState("Music for Airports", "Brian Eno", true, null, null, "player", "Music", null, false))
        signals.alarm.seed(at(16, 7, 30))
        GlanceBus.connected(true)
        GlanceBus.publish(
            Glance(
                listOf(
                    AppCount("signal", "Signal", NoteKind.Message, 3),
                    AppCount("gmail", "Gmail", NoteKind.Mail, 5),
                    AppCount("slack", "Slack", NoteKind.Message, 2),
                ),
                Note("signal", "Signal", "Anna", "Lunch at one still works?", now - 4 * 60_000L, NoteKind.Message),
                total = 10,
            ),
            now,
        )
        return signals
    }

    private fun shoot(h: Int, m: Int, mode: String) {
        val now = at(15, h, m)
        val signals = fixture(now)
        val ds = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "tin.tune.preferences_pb") }
        val home = HomeState(LayoutStore(ds), PageStore(ds), PinStore(ds), WidgetHost(rule.activity), scope)
        rule.waitUntil(5_000) { home.layout != null }
        val actions = Actions(rule.activity)
        val tune = Tune()
        val routes = GlanceRoutes({}, {}, {}, {}, {})
        rule.setContent {
            CompositionLocalProvider(LocalNow provides { now }, LocalTune provides tune) {
                Box(Modifier.fillMaxSize().background(Ng.bg)) {
                    HomeFrame(
                        gap = tune.gapDp.dp,
                        gestures = false,
                        onSwipeUp = {}, onSwipeDown = {}, onLongPress = {},
                        top = { Glance(signals.weather, tune, Modifier.padding(top = Dimen.space6), actions, routes) },
                        bottom = {},
                        stack = { HomeStack(home, signals, actions) },
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("src/test/screenshots/home-p0-%02d%02d-%s.png".format(h, m, mode))
    }
}
