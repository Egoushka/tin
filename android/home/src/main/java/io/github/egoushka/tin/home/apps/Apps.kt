package io.github.egoushka.tin.home.apps

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.LruCache
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

data class AppEntry(val label: String, val component: ComponentName, val user: UserHandle?) {
    val key: String get() = "${component.flattenToShortString()}#${user?.hashCode() ?: 0}"
}

/** Search over app labels: prefix beats word start beats substring; case and diacritics are ignored. */
object AppSearch {
    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    fun rank(label: String, query: String): Int? {
        val l = normalize(label)
        val q = normalize(query.trim())
        if (q.isEmpty()) return 3
        return when {
            l.startsWith(q) -> 0
            l.split(Regex("[\\s\\-_.:]+")).any { it.startsWith(q) } -> 1
            l.contains(q) -> 2
            else -> null
        }
    }

    fun <T> filter(items: List<T>, query: String, label: (T) -> String): List<T> =
        items.mapNotNull { item -> rank(label(item), query)?.let { it to item } }
            .sortedWith(compareBy<Pair<Int, T>> { it.first }.thenBy { normalize(label(it.second)) })
            .map { it.second }
}

class AppRepository(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val users = context.getSystemService(UserManager::class.java)

    /** Every launchable activity in every profile (work profile included), A–Z, Home itself excluded. */
    fun load(): List<AppEntry> = users.userProfiles.flatMap { user ->
        launcherApps.getActivityList(null, user)
            .filter { it.componentName.packageName != context.packageName }
            .map { AppEntry(it.label.toString(), it.componentName, user.takeIf { u -> u != Process.myUserHandle() }) }
    }.sortedBy { AppSearch.normalize(it.label) }

    private val loaded = MutableStateFlow<List<AppEntry>>(emptyList())

    /** The app list, kept current by [keepLoaded] so the drawer opens with it instead of loading on first open. */
    val list: StateFlow<List<AppEntry>> = loaded

    suspend fun keepLoaded() = changes().collect { loaded.value = withContext(Dispatchers.IO) { load() } }

    /** Draws every icon into the cache ahead of the drawer. */
    suspend fun warm(px: Int, tint: Int) = withContext(Dispatchers.IO) { loaded.value.forEach { icon(it, px, tint) } }

    /** Emits once now and again on every install, removal or change. */
    fun changes(): Flow<Unit> = callbackFlow {
        val cb = object : LauncherApps.Callback() {
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

    fun launch(app: AppEntry) {
        launcherApps.startMainActivity(app.component, app.user ?: Process.myUserHandle(), null, null)
    }

    private val icons = LruCache<String, Bitmap>(256)

    /** The icon if it is already drawn; never blocks. */
    fun cachedIcon(app: AppEntry, px: Int): Bitmap? = icons.get("${app.key}@$px")

    /**
     * The themed monochrome icon drawn in [tint] (Icons card: third-party apps in `ink` on the glass tile); apps
     * without one keep their own icon, inset to the same optical size.
     */
    fun icon(app: AppEntry, px: Int, tint: Int): Bitmap? {
        val key = "${app.key}@$px"
        icons.get(key)?.let { return it }
        val info = launcherApps.resolveActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN).setComponent(app.component),
            app.user ?: Process.myUserHandle(),
        ) ?: return null
        val drawable = info.getIcon(0)
        val mono = (drawable as? AdaptiveIconDrawable)?.monochrome
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (mono != null) {
            // The adaptive foreground spans 108 dp around a 72 dp safe zone; draw it so the zone fills the tile.
            val extra = (px * 0.25f).toInt()
            mono.setBounds(-extra, -extra, px + extra, px + extra)
            mono.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
            mono.draw(canvas)
        } else {
            drawable.setBounds(0, 0, px, px)
            drawable.draw(canvas)
        }
        icons.put(key, bmp)
        return bmp
    }
}
