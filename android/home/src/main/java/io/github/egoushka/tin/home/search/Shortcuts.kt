package io.github.egoushka.tin.home.search

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.ShortcutQuery
import android.content.pm.ShortcutInfo
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class ShortcutEntry(val id: String, val pkg: String, val label: String, val user: UserHandle?) {
    val key: String get() = "$pkg/$id#${user?.hashCode() ?: 0}"
}

/** App shortcuts (dynamic, manifest, pinned) of every profile. Home is the default launcher, so it may read them. */
class ShortcutRepository(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val users = context.getSystemService(UserManager::class.java)

    fun load(): List<ShortcutEntry> {
        if (!launcherApps.hasShortcutHostPermission()) return emptyList()
        val query = ShortcutQuery().setQueryFlags(
            ShortcutQuery.FLAG_MATCH_DYNAMIC or ShortcutQuery.FLAG_MATCH_MANIFEST or ShortcutQuery.FLAG_MATCH_PINNED,
        )
        return users.userProfiles.flatMap { user ->
            val found = runCatching { launcherApps.getShortcuts(query, user) }.getOrNull().orEmpty()
            found.filter { it.isEnabled && it.`package` != context.packageName }.mapNotNull { info ->
                val label = info.shortLabel?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ShortcutEntry(info.id, info.`package`, label, user.takeIf { it != Process.myUserHandle() })
            }
        }
    }

    /** Emits once now and again whenever shortcuts or packages change. */
    fun changes(): Flow<Unit> = callbackFlow {
        val cb = object : LauncherApps.Callback() {
            override fun onShortcutsChanged(p: String, s: MutableList<ShortcutInfo>, u: UserHandle) { trySend(Unit) }
            override fun onPackageRemoved(p: String?, u: UserHandle?) { trySend(Unit) }
            override fun onPackageAdded(p: String?, u: UserHandle?) { trySend(Unit) }
            override fun onPackageChanged(p: String?, u: UserHandle?) { trySend(Unit) }
            override fun onPackagesAvailable(p: Array<out String>?, u: UserHandle?, r: Boolean) { trySend(Unit) }
            override fun onPackagesUnavailable(p: Array<out String>?, u: UserHandle?, r: Boolean) { trySend(Unit) }
        }
        trySend(Unit)
        launcherApps.registerCallback(cb, Handler(Looper.getMainLooper()))
        awaitClose { launcherApps.unregisterCallback(cb) }
    }

    /** False when the system refused (the shortcut is gone, or Home is not the default launcher any more). */
    fun start(shortcut: ShortcutEntry): Boolean = runCatching {
        launcherApps.startShortcut(shortcut.pkg, shortcut.id, null, null, shortcut.user ?: Process.myUserHandle())
    }.isSuccess
}
