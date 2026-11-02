package io.github.egoushka.tin.home.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.egoushka.tin.home.Actions
import io.github.egoushka.tin.home.R
import io.github.egoushka.tin.home.layer.CardLight
import io.github.egoushka.tin.home.layer.Freshness
import io.github.egoushka.tin.home.layer.LayerCard
import io.github.egoushka.tin.home.layer.ageText
import io.github.egoushka.tin.home.layer.rememberNow
import io.github.egoushka.tin.home.signals.Glance
import io.github.egoushka.tin.home.signals.MusicSource
import io.github.egoushka.tin.home.signals.NoteKind
import io.github.egoushka.tin.home.signals.Signals
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.theme.Lucide
import io.github.egoushka.tin.home.theme.Ng
import io.github.egoushka.tin.home.theme.NgType
import io.github.egoushka.tin.home.theme.LocalTune
import kotlinx.coroutines.delay

private fun icon(kind: NoteKind) = when (kind) { NoteKind.Message -> "msg"; NoteKind.Mail -> "mail"; NoteKind.Other -> "bell" }

/** Unread counts per app and the latest message; asks for the listener grant while it is missing. */
@Composable
fun GlanceCard(signals: Signals, actions: Actions) {
    val connected by signals.listenerConnected.collectAsState()
    val state by signals.glance.collectAsState()
    val tune = LocalTune.current
    val now = rememberNow()
    if (!connected) {
        LayerCard("", null, CardLight(null)) {
            Row(
                Modifier.clickable(role = Role.Button) { actions.openListenerSettings() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(Lucide.icon("bell", 20f, tune.strokeDp), null, Modifier.size(Dimen.glyph))
                Spacer(Modifier.width(Dimen.space3))
                Column {
                    Text(stringResource(R.string.notif_access), style = NgType.title)
                    Text(stringResource(R.string.notif_access_hint), style = NgType.body)
                }
            }
        }
        return
    }
    val glance: Glance = state.glance
    val latest = glance.latest ?: return
    LayerCard("", null, CardLight(null), Modifier.clickable(role = Role.Button) { actions.openNotifications() }) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimen.space3)) {
            glance.apps.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(Lucide.icon(icon(a.kind), 16f, tune.strokeDp, Ng.ink2), null, Modifier.size(Dimen.glyphSmall))
                    Spacer(Modifier.width(Dimen.space1))
                    Text(a.app, style = NgType.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(Dimen.space1))
                    Text(a.count.toString(), style = NgType.body.copy(color = Ng.ink))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                latest.title, style = NgType.body.copy(color = Ng.ink, fontWeight = androidx.compose.ui.text.font.FontWeight(550)),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(Dimen.space2))
            Text(latest.text, style = NgType.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Dimen.space2))
            Text(ageText(Freshness.age(now, latest.whenMs)), style = NgType.body)
        }
    }
}

/** The active media session with transport controls; absent when nothing is playing or paused. */
@Composable
fun MusicCard(music: MusicSource) {
    val state = music.state.collectAsState().value ?: return
    val tune = LocalTune.current
    LaunchedEffect(Unit) {
        while (true) { music.refreshBuds(); delay(60_000) } // the earbuds' battery is polled, not pushed
    }
    LayerCard("", null, CardLight(null)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val art = state.art
            Box(
                Modifier
                    .size(Dimen.tile - Dimen.space3)
                    .clip(RoundedCornerShape(Dimen.radiusRow))
                    .background(Brush.linearGradient(listOf(Ng.glow2, Ng.glow1))),
            ) {
                if (art != null) Image(art.asImageBitmap(), null, Modifier.size(Dimen.tile - Dimen.space3), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.width(Dimen.space3))
            Column(Modifier.weight(1f)) {
                Text(state.title, style = NgType.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.artist, style = NgType.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    val buds = state.buds?.label().orEmpty()
                    if (buds.isNotEmpty()) {
                        val description = stringResource(R.string.buds_battery, buds)
                        Row(Modifier.semantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
                            Text(" · ", style = NgType.body)
                            Image(Lucide.icon("buds", 16f, tune.strokeDp, Ng.ink2), null, Modifier.size(Dimen.glyphSmall))
                            Spacer(Modifier.width(Dimen.space1))
                            Text(buds, style = NgType.body, maxLines = 1)
                        }
                    }
                }
            }
            Transport("prev", stringResource(R.string.music_prev)) { music.previous() }
            Transport(if (state.playing) "pause" else "play", stringResource(if (state.playing) R.string.music_pause else R.string.music_play)) { music.playPause() }
            Transport("next", stringResource(R.string.music_next)) { music.next() }
        }
    }
}

@Composable
private fun Transport(icon: String, label: String, onClick: () -> Unit) {
    val tune = LocalTune.current
    Box(
        Modifier
            .size(Dimen.target)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Image(Lucide.icon(icon, 24f, tune.strokeDp), null, Modifier.size(Dimen.glyphTile))
    }
}
