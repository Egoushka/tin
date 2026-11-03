package io.github.egoushka.tin.home

import android.content.Context
import android.provider.MediaStore
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import io.github.egoushka.tin.home.apps.AppEntry
import io.github.egoushka.tin.home.apps.AppPrefs
import io.github.egoushka.tin.home.apps.AppRepository
import io.github.egoushka.tin.home.apps.AppShortcut
import io.github.egoushka.tin.home.layout.HomeFrame
import io.github.egoushka.tin.home.tune.Tune
import io.github.egoushka.tin.home.ui.AppMenu
import io.github.egoushka.tin.home.ui.DockTile
import io.github.egoushka.tin.home.ui.LocalAppMenu
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** B4 to B6: the real dock tile inside the real frame; `DockSwipeTest` drove a bare box, which could not see F3. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h914dp-420dpi")
class DockTileTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val signal = AppEntry("Signal", "org.signal", "org.signal.Main", 0)
    private val selfie = AppShortcut("selfie", "Selfie")

    private var frameUp = 0
    private var menuOpened = 0
    private val launched = mutableListOf<String>()
    private val shortcutsStarted = mutableListOf<String>()

    private inner class FakeApps(private val listed: List<AppShortcut>) : AppRepository(context, AppPrefs(context)) {
        override suspend fun shortcuts(app: AppEntry) = listed
        override fun launch(app: AppEntry) { launched += app.key }
        override fun startShortcut(app: AppEntry, shortcut: AppShortcut): Result<Unit> {
            shortcutsStarted += shortcut.id
            return Result.success(Unit)
        }
    }

    private fun show(d: DockDefault, app: AppEntry?, shortcuts: List<AppShortcut> = emptyList()) = rule.setContent {
        val menu = AppMenu(open = { _, _ -> menuOpened++ }, pick = { menuOpened++ })
        CompositionLocalProvider(LocalAppMenu provides menu) {
            HomeFrame(
                gap = 12.dp,
                gestures = true,
                onSwipeUp = { frameUp++ },
                onSwipeDown = {},
                onLongPress = {},
                top = { Text("12:00") },
                bottom = {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        DockTile(Tune(), d, 0, app, ready = true, FakeApps(shortcuts), Actions(context))
                    }
                },
                stack = {},
            )
        }
    }

    private var app: AppEntry? = null

    private fun swipeUp() = rule.onNode(hasContentDescription(app?.label ?: context.getString(current.label))).performTouchInput {
        swipe(center, Offset(center.x, center.y - 120.dp.toPx()), durationMillis = 300)
    }

    private var current = DockDefault.Camera

    private fun start(d: DockDefault, a: AppEntry?, shortcuts: List<AppShortcut> = emptyList()) {
        current = d
        app = a
        show(d, a, shortcuts)
        rule.waitForIdle()
    }

    @Test fun `a swipe up on a docked app with no shortcuts opens neither All apps nor the menu, and is no tap`() {
        start(DockDefault.Camera, signal)
        swipeUp()
        rule.waitForIdle()
        assertEquals(listOf(0, 0, 0), listOf(frameUp, menuOpened, launched.size))
        assertEquals(null, shadowOf(context as android.app.Application).nextStartedActivity)
    }

    @Test fun `a tap on a docked app still launches it`() {
        start(DockDefault.Camera, signal)
        rule.onNode(hasContentDescription("Signal")).performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(listOf(signal.key), launched)
        assertEquals(0, frameUp)
    }

    @Test fun `a swipe up on a docked app with shortcuts starts the first and nothing else`() {
        start(DockDefault.Camera, signal, listOf(selfie, AppShortcut("video", "Video")))
        swipeUp()
        rule.waitForIdle()
        assertEquals(listOf("selfie"), shortcutsStarted)
        assertEquals(listOf(0, 0, 0), listOf(frameUp, menuOpened, launched.size))
    }

    @Test fun `the empty Camera slot swipes to video`() {
        start(DockDefault.Camera, null)
        swipeUp()
        rule.waitForIdle()
        assertEquals(MediaStore.INTENT_ACTION_VIDEO_CAMERA, shadowOf(context as android.app.Application).nextStartedActivity?.action)
        assertEquals(0, frameUp)
    }

    @Test fun `a tap on an empty slot whose default has no handler opens the picker and starts nothing`() {
        start(DockDefault.Maps, null)
        rule.onNode(hasContentDescription(context.getString(DockDefault.Maps.label))).performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(1, menuOpened)
        assertEquals(null, shadowOf(context as android.app.Application).nextStartedActivity)
    }

    @Test fun `a tap on an empty slot whose default has a handler starts it and opens no picker`() {
        val pm = shadowOf(context.packageManager)
        pm.addResolveInfoForIntent(DockDefault.Phone.intent(), android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply { packageName = "com.dialer"; name = "com.dialer.Main" }
        })
        start(DockDefault.Phone, null)
        rule.onNode(hasContentDescription(context.getString(DockDefault.Phone.label))).performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(0, menuOpened)
        assertEquals(android.content.Intent.ACTION_DIAL, shadowOf(context as android.app.Application).nextStartedActivity?.action)
    }

    @Test fun `an empty slot with no swipe default does nothing and does not open All apps`() {
        start(DockDefault.Maps, null)
        swipeUp()
        rule.waitForIdle()
        assertEquals(null, shadowOf(context as android.app.Application).nextStartedActivity)
        assertEquals(listOf(0, 0), listOf(frameUp, menuOpened))
    }
}
