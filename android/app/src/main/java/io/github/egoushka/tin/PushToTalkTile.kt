package io.github.egoushka.tin

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings push-to-talk: tap to record, tap again to transcribe. Needs SpeechService running. */
class PushToTalkTile : TileService() {
    override fun onStartListening() = render()

    override fun onClick() {
        SpeechService.instance?.toggle()
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val service = SpeechService.instance
        tile.state = when {
            service == null -> Tile.STATE_UNAVAILABLE
            service.state.startsWith("recording") -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = service?.state?.take(40) ?: "open tin"
        tile.updateTile()
    }

    companion object {
        fun refresh(context: Context) =
            requestListeningState(context, ComponentName(context, PushToTalkTile::class.java))
    }
}
