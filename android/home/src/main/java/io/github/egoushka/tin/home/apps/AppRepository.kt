package io.github.egoushka.tin.home.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** The apps the system reports: [LauncherApps] for packages, a broadcast for work-profile switches. */
class SystemApps(private val context: Context) : AppSource {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val users = context.getSystemService(UserManager::class.java)
    private val own = Process.myUserHandle()

    val ownUser: Long = users.getSerialNumberForUser(own)

    private fun handle(serial: Long): UserHandle =
        users.userProfiles.firstOrNull { users.getSerialNumberForUser(it) == serial } ?: own

    fun userOf(app: AppEntry): UserHandle = handle(app.user)

    override fun load(): List<AppEntry> = users.userProfiles.flatMap { user ->
        val serial = users.getSerialNumberForUser(user)
        launcherApps.getActivityList(null, user)
            .filter { it.componentName.packageName != context.packageName }
            .map { AppEntry(it.label.toString(), it.componentName.packageName, it.componentName.className, serial) }
    }

    override fun changes(): Flow<Set<String>> = callbackFlow {
        val callback = object : LauncherApps.Callback() {
            override fun onPackageRemoved(p: String?, u: UserHandle?) { trySend(setOfNotNull(p)) }
            override fun onPackageAdded(p: String?, u: UserHandle?) { trySend(setOfNotNull(p)) }
            override fun onPackageChanged(p: String?, u: UserHandle?) { trySend(setOfNotNull(p)) }
            override fun onPackagesAvailable(p: Array<out String>?, u: UserHandle?, r: Boolean) { trySend(p.orEmpty().toSet()) }
            override fun onPackagesUnavailable(p: Array<out String>?, u: UserHandle?, r: Boolean) { trySend(p.orEmpty().toSet()) }
        }
        // Switching a work profile on or off adds or removes every one of its apps without a package callback.
        val profiles = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { trySend(emptySet()) }
        }
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        context.registerReceiver(
            profiles,
            IntentFilter().apply {
                addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
                addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
                addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        trySend(emptySet())
        awaitClose {
            launcherApps.unregisterCallback(callback)
            context.unregisterReceiver(profiles)
        }
    }.buffer(Channel.UNLIMITED)

    fun launch(app: AppEntry) {
        launcherApps.startMainActivity(ComponentName(app.pkg, app.cls), userOf(app), null, null)
    }

    fun appInfo(app: AppEntry) {
        launcherApps.startAppDetailsActivity(ComponentName(app.pkg, app.cls), userOf(app), null, null)
    }

    /** The system asks before it removes anything. */
    fun uninstall(app: AppEntry) {
        context.startActivity(
            Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.pkg, null))
                .putExtra("android.intent.extra.USER", userOf(app))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Empty unless Home is the default home app: the API throws SecurityException for anyone else. */
    fun shortcuts(app: AppEntry): List<AppShortcut> = try {
        val query = LauncherApps.ShortcutQuery()
            .setPackage(app.pkg)
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
            )
        val raw = launcherApps.getShortcuts(query, userOf(app)).orEmpty().map(::raw)
        Shortcuts.pick(raw, app.cls)
    } catch (_: SecurityException) {
        emptyList()
    } catch (_: IllegalStateException) {
        emptyList()
    }

    fun startShortcut(app: AppEntry, id: String) {
        launcherApps.startShortcut(app.pkg, id, null, null, userOf(app))
    }

    /** Sets the whole pinned set of [pkg] for [user]: a pinned shortcut outlives the app removing it from its own list. */
    fun pinShortcuts(pkg: String, user: Long, ids: Set<String>) {
        launcherApps.pinShortcuts(pkg, ids.toList(), handle(user))
    }

    fun icon(app: AppEntry): android.graphics.drawable.Drawable? =
        launcherApps.resolveActivity(Intent(Intent.ACTION_MAIN).setComponent(ComponentName(app.pkg, app.cls)), userOf(app))?.getIcon(0)

    private fun raw(s: ShortcutInfo) = RawShortcut(
        id = s.id,
        label = (s.shortLabel ?: s.longLabel)?.toString(),
        enabled = s.isEnabled,
        activity = s.activity?.className,
        manifest = s.isDeclaredInManifest,
        dynamic = s.isDynamic,
        pinned = s.isPinned,
        rank = s.rank,
    )
}

/** What the UI talks to: the live list, the dock, hide, icons and the long-press actions. */
open class AppRepository(context: Context, private val prefs: AppPrefs) {
    private val system = SystemApps(context)
    private val model = AppList(system, prefs.saved.map { it.hidden }.distinctUntilChanged())

    /** Everything, hidden included, for the dock and the hidden list. */
    val state: StateFlow<Apps> = model.state

    /** What the drawer and search show. */
    val list: StateFlow<List<AppEntry>> = model.visible

    /** Runs while Home is in front of the process: the drawer opens with the list already loaded. */
    suspend fun keepLoaded() = model.keep()

    /** Draws every icon into the cache ahead of the drawer and the dock. */
    suspend fun warm(px: Int, tint: Int) = withContext(Dispatchers.IO) { model.state.value.all.forEach { icon(it, px, tint) } }

    /**
     * The five docked apps: the stored ones, else the apps behind today's handlers ([handlers], one per default job).
     * Null until the list and the stored dock have both been read.
     */
    fun dock(handlers: () -> List<Pair<String, String?>?>): Flow<DockSlots?> =
        combine(prefs.saved.map { it.dock }.distinctUntilChanged(), model.state) { raw, apps ->
            if (!apps.loaded) null else DockSlots.decode(raw, DockDefaults.keys(handlers(), apps.all, system.ownUser))
        }.distinctUntilChanged().flowOn(Dispatchers.IO)

    suspend fun saveDock(slots: DockSlots) = prefs.saveDock(slots)
    suspend fun hide(app: AppEntry) = prefs.hide(app.key)
    suspend fun show(key: String) = prefs.show(key)

    open fun launch(app: AppEntry) = system.launch(app)
    fun appInfo(app: AppEntry) = runCatching { system.appInfo(app) }
    fun uninstall(app: AppEntry) = runCatching { system.uninstall(app) }
    open suspend fun shortcuts(app: AppEntry): List<AppShortcut> = withContext(Dispatchers.IO) { system.shortcuts(app) }
    open fun startShortcut(app: AppEntry, shortcut: AppShortcut) = runCatching { system.startShortcut(app, shortcut.id) }
    suspend fun pinShortcuts(pkg: String, user: Long, ids: Set<String>) =
        withContext(Dispatchers.IO) { runCatching { system.pinShortcuts(pkg, user, ids) } }

    private val icons = LruCache<String, Bitmap>(256)

    private fun cacheKey(app: AppEntry, px: Int) = "${app.key}@$px#${app.rev}"

    /** The icon if it is already drawn; never blocks. */
    fun cachedIcon(app: AppEntry, px: Int): Bitmap? = icons.get(cacheKey(app, px))

    /**
     * The themed monochrome icon drawn in [tint] (Icons card: third-party apps in `ink` on the glass tile); apps
     * without one keep their own icon, inset to the same optical size.
     */
    fun icon(app: AppEntry, px: Int, tint: Int): Bitmap? {
        val key = cacheKey(app, px)
        icons.get(key)?.let { return it }
        val drawable = system.icon(app) ?: return null
        val bmp = renderIcon(drawable, px, tint)
        icons.put(key, bmp)
        return bmp
    }
}
