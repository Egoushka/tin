package io.github.egoushka.tin.home

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.provider.AlarmClock
import android.provider.Settings
import io.github.egoushka.tin.home.apps.AppEntry
import io.github.egoushka.tin.home.layout.DefaultPins
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** B9: the apps a new Home pins are the phone's own defaults, not a guess by name. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DefaultPinsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun app(pkg: String) = AppEntry(pkg, pkg, "$pkg.Main", 0)
    private fun category(name: String) = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, name)

    private fun handle(intent: Intent, pkg: String) {
        val info = ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = pkg; name = "$pkg.Main" } }
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, info)
    }

    @Test fun `each handled job pins the app that handles it, in job order`() {
        handle(category(Intent.CATEGORY_APP_EMAIL), "mail")
        handle(Intent(Settings.ACTION_SETTINGS), "settings")
        handle(Intent(AlarmClock.ACTION_SHOW_ALARMS), "clock")
        val installed = listOf(app("clock"), app("mail"), app("settings"), app("other"))
        assertEquals(listOf(app("settings").key, app("mail").key, app("clock").key), DefaultPins.keys(context, installed))
    }

    @Test fun `a job nobody handles, or only the system chooser handles, adds nothing`() {
        handle(category(Intent.CATEGORY_APP_MUSIC), "android")
        assertEquals(emptyList<String>(), DefaultPins.keys(context, listOf(app("android"), app("other"))))
    }

    @Test fun `a handler that is not a launcher app adds nothing`() {
        handle(category(Intent.CATEGORY_APP_EMAIL), "hidden")
        assertEquals(emptyList<String>(), DefaultPins.keys(context, listOf(app("other"))))
    }

    @Test fun `an app that handles two jobs is listed once`() {
        handle(category(Intent.CATEGORY_APP_GALLERY), "photos")
        handle(category(Intent.CATEGORY_APP_MUSIC), "photos")
        assertEquals(listOf(app("photos").key), DefaultPins.keys(context, listOf(app("photos"))))
    }
}
