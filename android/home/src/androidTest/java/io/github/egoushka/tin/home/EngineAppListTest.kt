package io.github.egoushka.tin.home

import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.egoushka.tin.home.apps.AppEntry
import io.github.egoushka.tin.home.apps.AppPrefs
import io.github.egoushka.tin.home.apps.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S0.3 (docs/specs/2026-11-04-home-on-kvaesitso.md): every launchable app of every profile shows once in the drawer, read
 * from the engine's `:data:applications`. The engine is Koin-started by [HomeApplication], which the instrumentation runs.
 */
@RunWith(AndroidJUnit4::class)
class EngineAppListTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val users = context.getSystemService(UserManager::class.java)
    private val launcherApps = context.getSystemService(LauncherApps::class.java)

    // What the engine leaves out by design: a locked profile, and Home itself (a ".debug" build id is kept, as its own).
    private fun launchable(): List<String> = users.userProfiles
        .filter { users.isUserUnlocked(it) }
        .flatMap { user ->
            val serial = users.getSerialNumberForUser(user)
            launcherApps.getActivityList(null, user)
                .filterNot { it.applicationInfo.packageName == context.packageName && !context.packageName.endsWith(".debug") }
                .map { "${it.componentName.packageName}/${it.componentName.className}#$serial" }
        }
        .sorted()

    private fun keys(apps: List<AppEntry>) = apps.map { it.key }.sorted()

    @Test
    fun drawerListsEveryLauncherActivityOnce() {
        runBlocking {
            val repo = AppRepository(context, AppPrefs(context))
            val loading = launch(Dispatchers.Default) { repo.keepLoaded() }
            try {
                val launchable = launchable()
                // An empty list would match the engine's empty start.
                assertTrue("LauncherApps lists no activity to compare with", launchable.isNotEmpty())

                // The drawer leaves out what the user hid, which is nothing on a fresh emulator. Sorted lists are
                // compared, not sets, so an app listed twice fails.
                fun expected() = launchable - repo.state.value.hidden
                val settled = withTimeoutOrNull(30_000) { repo.list.first { keys(it) == expected() } }
                if (settled == null) {
                    val actual = keys(repo.list.value)
                    val twice = actual.groupBy { it }.filterValues { it.size > 1 }.keys
                    fail(
                        "drawer differs from LauncherApps after 30 s: missing ${expected() - actual.toSet()}, " +
                            "extra ${actual - expected().toSet()}, listed twice $twice",
                    )
                }
            } finally {
                loading.cancelAndJoin()
            }
        }
    }
}
