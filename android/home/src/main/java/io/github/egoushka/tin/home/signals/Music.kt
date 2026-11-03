package io.github.egoushka.tin.home.signals

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** [app] is the player's name and [sessionActivity] its own screen; [launchable] says [packageName] has a launcher entry. */
data class MusicState(
    val title: String,
    val artist: String,
    val playing: Boolean,
    val art: Bitmap?,
    val buds: Buds?,
    val packageName: String,
    val app: String,
    val sessionActivity: PendingIntent?,
    val launchable: Boolean,
)

/** Battery of the earbuds on the current output, per ear when the device reports it. */
data class Buds(val left: Int?, val right: Int?, val single: Int?) {
    /** "80 · 76%" with both ears, else the one reading we have. */
    fun label(): String = when {
        left != null && right != null -> "$left · $right%"
        else -> (left ?: right ?: single)?.let { "$it%" }.orEmpty()
    }
}

/** The active media session, as one flow. Needs the notification listener grant (see [TinListener]). */
class MusicSource(private val context: Context) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, TinListener::class.java)
    private val _state = MutableStateFlow<MusicState?>(null)
    val state: StateFlow<MusicState?> = _state

    private var controller: MediaController? = null
    private var buds: Buds? = null
    private var started = false

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = pick(manager.getActiveSessions(component))
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { pick(it.orEmpty()) }

    fun start() {
        if (started) return
        try {
            manager.addOnActiveSessionsChangedListener(sessions, component)
            started = true
            pick(manager.getActiveSessions(component))
        } catch (_: SecurityException) {
            _state.value = null // no listener grant yet
        }
    }

    fun stop() {
        if (!started) return
        manager.removeOnActiveSessionsChangedListener(sessions)
        controller?.unregisterCallback(callback)
        controller = null
        started = false
        _state.value = null
    }

    /** Called by the UI on a timer while music shows; the battery is not pushed by the system. */
    fun refreshBuds() {
        val next = BudsReader.read(context)
        if (next != buds) { buds = next; publish() }
    }

    private fun pick(list: List<MediaController>) {
        val next = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (next?.sessionToken == controller?.sessionToken) { publish(); return }
        controller?.unregisterCallback(callback)
        controller = next
        next?.registerCallback(callback)
        publish()
    }

    private fun publish() {
        val c = controller
        val meta = c?.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
        _state.value = if (c == null || title.isNullOrBlank()) null else {
            val player = playerOf(c.packageName)
            MusicState(
                title = title,
                artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
                playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
                art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART),
                buds = buds,
                packageName = player.pkg,
                app = player.label,
                sessionActivity = c.sessionActivity,
                launchable = player.launchable,
            )
        }
    }

    private class Player(val pkg: String, val label: String, val launchable: Boolean)

    private var player: Player? = null

    /** The session's app, looked up once per package: publish runs on every playback tick. */
    private fun playerOf(pkg: String): Player = player?.takeIf { it.pkg == pkg } ?: run {
        val pm = context.packageManager
        val label = runCatching { pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)).loadLabel(pm).toString() }.getOrDefault("")
        Player(pkg, label, pm.getLaunchIntentForPackage(pkg) != null)
    }.also { player = it }

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() = controller?.transportControls?.skipToNext()
    fun previous() = controller?.transportControls?.skipToPrevious()
}
