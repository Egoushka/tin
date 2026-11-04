package io.github.egoushka.tin.home.apps

import android.content.Context
import android.graphics.Bitmap
import android.os.UserHandle
import android.os.UserManager
import de.mm20.launcher2.icons.IconService
import de.mm20.launcher2.preferences.ui.IconSettings
import de.mm20.launcher2.search.Application
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import de.mm20.launcher2.applications.AppRepository as EngineAppRepository

/**
 * Kvaesitso's app list (every unlocked profile, Home excluded) as an [AppSource]. The engine swaps in a new entry only
 * for a package that changed, so [changes] names the packages whose entries are new objects.
 */
class EngineApps(context: Context) : AppSource, KoinComponent {
    private val repository: EngineAppRepository by inject()
    private val users = context.getSystemService(UserManager::class.java)
    private val serials = ConcurrentHashMap<UserHandle, Long>()

    @Volatile private var latest: Map<String, Application> = emptyMap()

    private fun serial(user: UserHandle) = serials.getOrPut(user) { users.getSerialNumberForUser(user) }

    private fun keyOf(app: Application) = "${app.componentName.packageName}/${app.componentName.className}#${serial(app.user)}"

    override fun load(): List<AppEntry> = latest.map { (_, app) ->
        AppEntry(app.label, app.componentName.packageName, app.componentName.className, serial(app.user))
    }

    // The engine starts from an empty list before its first query; a phone always has some launchable app.
    override fun changes(): Flow<Set<String>> = flow {
        var before: Map<String, Application>? = null
        repository.findMany().dropWhile { it.isEmpty() }.collect { list ->
            val now = list.associateBy(::keyOf)
            latest = now
            val changed = before?.let { old ->
                (old.keys + now.keys).filter { old[it] !== now[it] }
                    .mapTo(HashSet()) { (now[it] ?: old.getValue(it)).componentName.packageName }
            }
            before = now
            emit(changed.orEmpty())
        }
    }

    fun application(app: AppEntry): Application? = latest[app.key]

    private val starting = Mutex()
    @Volatile private var service: IconService? = null

    // Home draws every app that has a monochrome layer in one ink, so the engine's themed icons are always on. The
    // service builds its providers from the setting it first reads, so it is created only once the setting is stored.
    private suspend fun icons(): IconService = service ?: starting.withLock {
        service ?: run {
            val settings = get<IconSettings>()
            settings.setThemedIcons(true)
            settings.first { it.themedIcons }
            get<IconService>().also { service = it }
        }
    }

    /** The engine's icon for [app] on a [px] tile, drawn the way the drawer shows it ([tileIcon]). */
    suspend fun icon(app: AppEntry, px: Int, tint: Int): Bitmap? {
        val application = application(app) ?: return null
        return icons().tileIcon(application, px, tint)
    }
}
