package io.github.egoushka.tin.home.layout

import android.appwidget.AppWidgetHostView
import android.os.Bundle
import android.util.SizeF
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.egoushka.tin.home.Actions
import io.github.egoushka.tin.home.R
import io.github.egoushka.tin.home.signals.Signals
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.theme.Lucide
import io.github.egoushka.tin.home.theme.LocalTune
import io.github.egoushka.tin.home.theme.Ng
import io.github.egoushka.tin.home.theme.NgType
import io.github.egoushka.tin.home.theme.glass
import io.github.egoushka.tin.home.ui.AlarmCard
import io.github.egoushka.tin.home.ui.GlanceCard
import io.github.egoushka.tin.home.ui.HomelabCard
import io.github.egoushka.tin.home.ui.IconButton
import io.github.egoushka.tin.home.ui.MusicCard
import io.github.egoushka.tin.home.ui.NeedsYouCard
import io.github.egoushka.tin.home.ui.TodayCard

object StackTags {
    const val Grant = "home.grant"
    const val Widget = "home.widget"
}

/** The cards and widgets in the order he chose; in edit mode each gets a header with its controls. */
@Composable
fun HomeStack(home: HomeState, signals: Signals, actions: Actions, onToday: () -> Unit = {}) {
    val layout = home.layout ?: return // the saved layout has not arrived: show nothing rather than the wrong cards
    val visible = layout.visible
    if (home.editing) {
        Text(stringResource(R.string.edit_hint), style = NgType.body)
    }
    visible.forEachIndexed { i, slot ->
        key(slot.code) {
            if (home.editing) {
                EditedSlot(slot, home, layout, signals, actions, first = i == 0, last = i == visible.lastIndex)
            } else {
                SlotContent(slot, home, layout, signals, actions, editing = false, onToday = onToday)
            }
        }
    }
    if (home.editing) HiddenTray(layout.hiddenSlots, home)
}

@Composable
private fun SlotContent(slot: Slot, home: HomeState, layout: HomeLayout, signals: Signals, actions: Actions, editing: Boolean, onToday: () -> Unit = {}) {
    when (slot) {
        is Slot.Widget -> WidgetCell(
            slot.id, layout.rows[slot.id], home.widgets, editing,
            onRemove = { home.removeWidget(slot.id) }, sideways = home.pages.count > 0,
        )
        is Slot.Card -> when (slot.id) {
            CardId.NeedsYou -> NeedsYouCard(signals.needsYou, actions)
            CardId.Today -> {
                val state by signals.calendar.state.collectAsState()
                // Before the first read nothing is known, so TodayCard keeps quiet instead of flashing a prompt.
                if (!state.granted && state.at != 0L) {
                    GrantRow("cal", R.string.calendar_access, { actions.askCalendar() }, hide = hideOf(slot, home, editing))
                } else {
                    TodayCard(signals.calendar, actions, onToday)
                }
            }
            CardId.Glance -> {
                val connected by signals.listenerConnected.collectAsState()
                if (!connected) {
                    GrantRow("bell", R.string.notif_access, { actions.openListenerSettings() }, hide = hideOf(slot, home, editing))
                } else {
                    GlanceCard(signals, actions)
                }
            }
            CardId.Music -> {
                val connected by signals.listenerConnected.collectAsState()
                val playing = signals.music.state.collectAsState().value
                when {
                    // The glance row already asks for the same grant; two identical rows would be noise.
                    !connected -> if (Slot.Card(CardId.Glance) !in layout.visible) {
                        GrantRow("bell", R.string.notif_access, { actions.openListenerSettings() }, hide = hideOf(slot, home, editing))
                    }
                    playing == null -> GrantRow("play", R.string.music_empty, onGrant = null, hide = hideOf(slot, home, editing))
                    else -> MusicCard(signals.music, actions)
                }
            }
            CardId.Alarm -> AlarmCard(signals.alarm, actions)
            CardId.Homelab -> HomelabCard(signals.homelab)
        }
    }
}

/** The row's own hide control; in edit mode the slot's header already has one. */
@Composable
private fun hideOf(slot: Slot, home: HomeState, editing: Boolean): (() -> Unit)? =
    if (editing) null else ({ home.hide(slot) })

/** One compact row in place of a full card that can only ask for a grant. */
@Composable
internal fun GrantRow(icon: String, text: Int, onGrant: (() -> Unit)?, hide: (() -> Unit)?) {
    val tune = LocalTune.current
    val name = stringResource(text)
    Row(
        Modifier
            .testTag(StackTags.Grant)
            .fillMaxWidth()
            .height(Dimen.target)
            .glass(RoundedCornerShape(Dimen.radiusCard)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .then(if (onGrant != null) Modifier.clickable(role = Role.Button, onClick = onGrant) else Modifier)
                .padding(start = Dimen.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(Lucide.icon(icon, 20f, tune.strokeDp), null, Modifier.size(Dimen.glyph))
            Spacer(Modifier.width(Dimen.space3))
            Text(name, style = NgType.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (hide != null) IconButton("x", stringResource(R.string.edit_hide, name), hide)
    }
}

/** A widget in a glass frame, on the stack or on a page; [sideways] lets a sideways drag over it turn the page. */
@Composable
internal fun WidgetCell(id: Int, rows: Int?, host: WidgetHost, editing: Boolean, onRemove: () -> Unit, sideways: Boolean) {
    val known = remember(id) { host.label(id) != null }
    if (!known) {
        // The provider's app is gone; the sweep drops the slot once the host forgets the id.
        GrantRow(
            "warn", R.string.widget_unavailable, onGrant = null,
            hide = if (editing) null else onRemove,
        )
        return
    }
    val range = remember(id) { host.rowRange(id) ?: 1..HomeLayout.MAX_ROWS }
    val height = HomeLayout.heightDp((rows ?: range.first).coerceIn(1, HomeLayout.MAX_ROWS)).dp
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(IntSize.Zero) }
    val shape = RoundedCornerShape(Dimen.radiusCard)
    Box(
        Modifier
            .testTag(StackTags.Widget)
            .fillMaxWidth()
            .height(height)
            .glass(shape)
            .clip(shape)
            .noHomeGestures()
            .onSizeChanged { size = it },
    ) {
        AndroidView(
            factory = { ctx -> host.createView(ctx, id) ?: View(ctx) },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                (view as? StackWidgetView)?.sideways = sideways
                if (view is AppWidgetHostView && size != IntSize.Zero) {
                    view.updateAppWidgetSize(Bundle.EMPTY, listOf(SizeF(size.width / density, size.height / density)))
                }
            },
        )
        if (editing) Blocker()
    }
}

/** Swallows taps so a card or widget cannot fire while it is being arranged; drags still reach the scroll. */
@Composable
private fun Blocker() {
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } })
}

@Composable
private fun slotName(slot: Slot, home: HomeState): String = when (slot) {
    is Slot.Card -> stringResource(
        when (slot.id) {
            CardId.NeedsYou -> R.string.needs_you
            CardId.Today -> R.string.today
            CardId.Glance -> R.string.card_notifications
            CardId.Music -> R.string.card_music
            CardId.Alarm -> R.string.alarm
            CardId.Homelab -> R.string.homelab
        },
    )
    is Slot.Widget -> remember(slot.id) { home.widgets.label(slot.id) } ?: stringResource(R.string.widget_unavailable)
}

@Composable
private fun EditedSlot(
    slot: Slot, home: HomeState, layout: HomeLayout, signals: Signals, actions: Actions, first: Boolean, last: Boolean,
) {
    val name = slotName(slot, home)
    Column(verticalArrangement = Arrangement.spacedBy(Dimen.space1)) {
        Row(Modifier.fillMaxWidth().heightIn(min = Dimen.target), verticalAlignment = Alignment.CenterVertically) {
            Text(
                name, style = NgType.label.copy(color = Ng.ink2), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = Dimen.space2),
            )
            if (slot is Slot.Widget) {
                val range = remember(slot.id) { home.widgets.rowRange(slot.id) ?: 1..HomeLayout.MAX_ROWS }
                val rows = (layout.rows[slot.id] ?: range.first).coerceIn(range)
                StepButton("−", stringResource(R.string.edit_shorter, name), enabled = rows > range.first) { home.resize(slot.id, rows - 1) }
                StepButton("+", stringResource(R.string.edit_taller, name), enabled = rows < range.last) { home.resize(slot.id, rows + 1) }
            }
            StepButton("↑", stringResource(R.string.edit_up, name), enabled = !first) { home.move(slot, -1) }
            StepButton("↓", stringResource(R.string.edit_down, name), enabled = !last) { home.move(slot, 1) }
            if (slot is Slot.Widget) {
                IconButton("x", stringResource(R.string.edit_remove, name)) { home.removeWidget(slot.id) }
            } else {
                IconButton("x", stringResource(R.string.edit_hide, name)) { home.hide(slot) }
            }
        }
        Box {
            SlotContent(slot, home, layout, signals, actions, editing = true)
            // Over cards only; a widget draws its own blocker inside its frame.
            if (slot is Slot.Card) Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { } })
        }
    }
}

/** A glyph-sized control with a text face: the Lucide set has no arrows or plus. */
@Composable
internal fun StepButton(face: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .size(Dimen.target)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(face, style = NgType.title.copy(color = if (enabled) Ng.ink else Ng.ink2.copy(alpha = 0.4f)))
    }
}

@Composable
private fun HiddenTray(hidden: List<Slot>, home: HomeState) {
    if (hidden.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Dimen.space1)) {
        Text(
            stringResource(R.string.edit_hidden), style = NgType.label.copy(color = Ng.ink2),
            modifier = Modifier.padding(start = Dimen.space2),
        )
        hidden.forEach { slot ->
            key(slot.code) {
                val name = slotName(slot, home)
                val show = stringResource(R.string.edit_show, name)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimen.target)
                        .clip(RoundedCornerShape(Dimen.radiusCard))
                        .clickable(role = Role.Button) { home.show(slot) }
                        .semantics { contentDescription = show }
                        .padding(horizontal = Dimen.space4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, style = NgType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("+", style = NgType.title)
                }
            }
        }
    }
}

/** What replaces the pins, dock and search bar while Home is being edited; on an extra page ([page]) it adds to that page. */
@Composable
fun EditBar(home: HomeState, page: Int?, onTune: () -> Unit, onGestures: () -> Unit) {
    val gap = Dimen.space2
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            Pill(stringResource(R.string.edit_add_widget), Modifier.weight(1f)) { home.openPicker(Picker.Widgets, page) }
            Pill(stringResource(R.string.edit_add_app), Modifier.weight(1f)) { home.openPicker(Picker.Apps, page) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            Pill(stringResource(R.string.tune), Modifier.weight(1f), onClick = onTune)
            Pill(stringResource(R.string.gestures), Modifier.weight(1f), onClick = onGestures)
            Pill(stringResource(R.string.edit_done), Modifier.weight(1f), accent = true) { home.edit(false) }
        }
    }
}

@Composable
internal fun Pill(text: String, modifier: Modifier, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = Dimen.tile)
            .let { if (accent) it.clip(RoundedCornerShape(Dimen.radiusCard)).background(Ng.accent) else it.glass(RoundedCornerShape(Dimen.radiusCard)) }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Dimen.space3, vertical = Dimen.space2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = NgType.title.copy(color = if (accent) Ng.onAccent else Ng.ink),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}
