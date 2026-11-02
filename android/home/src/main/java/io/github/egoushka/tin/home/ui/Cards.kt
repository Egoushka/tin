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
import android.text.format.DateFormat
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import io.github.egoushka.tin.home.signals.AlarmSource
import io.github.egoushka.tin.home.signals.NeedsYouSource
import io.github.egoushka.tin.home.signals.Agenda
import io.github.egoushka.tin.home.signals.CalendarSource
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

private fun clock(context: android.content.Context, ms: Long): String =
    DateFormat.getTimeFormat(context).format(java.util.Date(ms))

/** The rest of today: current event teal, clashes marked warn; asks for the calendar grant while it is missing. */
@Composable
fun TodayCard(calendar: CalendarSource, actions: Actions) {
    val state by calendar.state.collectAsState()
    val tune = LocalTune.current
    val context = LocalContext.current
    val now = rememberNow()
    val label = stringResource(R.string.today)
    if (!state.granted) {
        if (state.at == 0L) return // first read not back yet; no flash of the prompt
        LayerCard(label, null, CardLight(null), Modifier.clickable(role = Role.Button) { actions.askCalendar() }) {
            Text(stringResource(R.string.calendar_access), style = NgType.title)
            Text(stringResource(R.string.calendar_access_hint), style = NgType.body)
        }
        return
    }
    val agenda = Agenda.of(state.events, now)
    val stale = Freshness.isStale(now, state.at, CalendarSource.INTERVAL_MS)
    LayerCard(
        label, Freshness.age(now, state.at),
        CardLight(if (agenda.hasCurrent) Ng.signal else null, stale),
    ) {
        if (agenda.items.isEmpty()) {
            Text(stringResource(R.string.today_empty), style = NgType.body)
        } else {
            agenda.items.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(clock(context, row.event.beginMs), style = NgType.body.copy(color = if (row.current) Ng.signal else Ng.ink2), modifier = Modifier.width(Dimen.target + Dimen.space2))
                    Text(
                        row.event.title, style = NgType.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (row.clash) {
                        val clash = stringResource(R.string.clash)
                        Spacer(Modifier.width(Dimen.space2))
                        Image(
                            Lucide.icon("warn", 16f, tune.strokeDp, Ng.warn), null,
                            Modifier.size(Dimen.glyphSmall).semantics { contentDescription = clash },
                        )
                    }
                }
            }
            if (agenda.more > 0) Text(stringResource(R.string.today_more, agenda.more), style = NgType.body)
        }
    }
}

/** What waits for Yehor in the approvals app: lit while anything is open, oldest first, tap opens the queue. */
@Composable
fun NeedsYouCard(source: NeedsYouSource, actions: Actions) {
    val state by source.state.collectAsState()
    if (state.status == NeedsYouSource.Status.Loading) return // first read not back yet
    val now = rememberNow()
    val label = stringResource(R.string.needs_you)
    val needs = state.needs
    val unavailable = state.status == NeedsYouSource.Status.Unavailable
    val stale = !unavailable && Freshness.isStale(now, state.at, NeedsYouSource.INTERVAL_MS)
    LayerCard(
        label, if (unavailable) null else Freshness.age(now, state.at),
        CardLight(if (needs.lit) Ng.glow1 else null, stale),
        Modifier.clickable(role = Role.Button) { actions.openApprovals() },
    ) {
        when {
            unavailable -> Text(stringResource(R.string.needs_unavailable), style = NgType.body)
            !needs.lit -> Text(stringResource(R.string.needs_empty), style = NgType.body)
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(needs.open.toString(), style = NgType.figure)
                Spacer(Modifier.width(Dimen.space4))
                Column(Modifier.weight(1f)) {
                    needs.rows.forEach { item ->
                        Row {
                            Text(item.title, style = NgType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(Dimen.space2))
                            Text(item.source, style = NgType.body)
                        }
                    }
                    val tail = listOfNotNull(
                        needs.more.takeIf { it > 0 }?.let { stringResource(R.string.needs_more, it) },
                        needs.held.takeIf { it > 0 }?.let { stringResource(R.string.needs_held, it) },
                    )
                    if (tail.isNotEmpty()) Text(tail.joinToString(" · "), style = NgType.body)
                }
            }
        }
    }
}

/** The next alarm clock; absent when none is set. */
@Composable
fun AlarmCard(alarm: AlarmSource) {
    val next = alarm.next.collectAsState().value ?: return
    val tune = LocalTune.current
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val now = rememberNow()
    val inMin = ((next - now) / 60_000).toInt().coerceAtLeast(0)
    val relative = when {
        inMin >= 24 * 60 -> null
        inMin >= 60 -> stringResource(R.string.alarm_in_hm, inMin / 60, inMin % 60)
        else -> stringResource(R.string.alarm_in_min, inMin)
    }
    val sameDay = java.time.Instant.ofEpochMilli(next).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == java.time.LocalDate.now()
    val at = if (sameDay) clock(context, next) else {
        val pattern = DateFormat.getBestDateTimePattern(locale, if (DateFormat.is24HourFormat(context)) "EEEHm" else "EEEjm")
        android.icu.text.SimpleDateFormat(pattern, locale).format(java.util.Date(next))
    }
    LayerCard("", null, CardLight(null)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(Lucide.icon("alarm", 20f, tune.strokeDp), null, Modifier.size(Dimen.glyph))
            Spacer(Modifier.width(Dimen.space3))
            Text(stringResource(R.string.alarm), style = NgType.body, modifier = Modifier.weight(1f))
            Text(at, style = NgType.title)
            if (relative != null) {
                Spacer(Modifier.width(Dimen.space2))
                Text(relative, style = NgType.body)
            }
        }
    }
}
