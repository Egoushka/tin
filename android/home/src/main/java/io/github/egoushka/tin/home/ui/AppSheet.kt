package io.github.egoushka.tin.home.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.egoushka.tin.home.DockDefault
import io.github.egoushka.tin.home.R
import io.github.egoushka.tin.home.apps.AppEntry
import io.github.egoushka.tin.home.apps.AppRepository
import io.github.egoushka.tin.home.apps.AppShortcut
import io.github.egoushka.tin.home.apps.DockSlots
import io.github.egoushka.tin.home.layout.PinStore
import io.github.egoushka.tin.home.layout.pinToHome
import io.github.egoushka.tin.home.layout.unpinFromHome
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.theme.LocalTune
import io.github.egoushka.tin.home.theme.Lucide
import io.github.egoushka.tin.home.theme.NgType
import io.github.egoushka.tin.home.theme.glass
import kotlinx.coroutines.flow.StateFlow

/**
 * The long-press menu of an app: its shortcuts, then info, dock, Home, hide and uninstall. [dockSlot] is the app's place
 * in the dock, null when the press was on a drawer tile. [pins] are the keys pinned above the dock.
 */
@Composable
fun AppSheet(
    app: AppEntry,
    apps: AppRepository,
    dock: StateFlow<DockSlots?>,
    dockSlot: Int?,
    pins: List<String>,
    onPin: (slot: Int) -> Unit,
    onReplace: () -> Unit,
    onHide: () -> Unit,
    onClose: () -> Unit,
) {
    val tune = LocalTune.current
    val context = LocalContext.current
    val shortcuts by produceState(emptyList<AppShortcut>(), app.key) { value = apps.shortcuts(app) }
    var choosing by remember { mutableStateOf(false) }
    SheetFrame(tune, onClose) {
        Row(Modifier.padding(start = Dimen.space3), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(Dimen.tile).glass(CircleShape), contentAlignment = Alignment.Center) {
                AppIcon(app, apps, Modifier.size(Dimen.glyphTile * 1.5f))
            }
            Spacer(Modifier.width(Dimen.space3))
            Text(app.label, style = NgType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton("x", stringResource(R.string.close), onClose)
        }

        shortcuts.forEach { s ->
            SheetRow("arrow-up-right", s.label) { apps.startShortcut(app, s); onClose() }
        }

        SheetRow("info", stringResource(R.string.app_info)) { apps.appInfo(app); onClose() }
        when {
            dockSlot != null -> SheetRow("pin", stringResource(R.string.app_replace), onReplace)
            choosing -> SlotChooser(apps, dock, onPin)
            else -> SheetRow("pin", stringResource(R.string.app_pin)) { choosing = true }
        }
        when {
            app.key in pins -> SheetRow("home", stringResource(R.string.app_unpin_home)) { unpinFromHome(context, app); onClose() }
            pins.size < PinStore.MAX -> SheetRow("home", stringResource(R.string.app_pin_home)) { pinToHome(context, app); onClose() }
        }
        SheetRow("eye-off", stringResource(R.string.app_hide), onHide)
        SheetRow("trash-2", stringResource(R.string.app_uninstall)) { apps.uninstall(app); onClose() }
    }
}

/** Five circles in the dock's order, each showing what sits there now; a tap puts the app in that slot. */
@Composable
private fun SlotChooser(apps: AppRepository, dock: StateFlow<DockSlots?>, onPick: (Int) -> Unit) {
    val tune = LocalTune.current
    val slots by dock.collectAsState()
    val state by apps.state.collectAsState()
    Column(Modifier.padding(horizontal = Dimen.space3, vertical = Dimen.space2), verticalArrangement = Arrangement.spacedBy(Dimen.space2)) {
        Text(stringResource(R.string.dock_choose_slot).uppercase(), style = NgType.label)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DockDefault.entries.forEachIndexed { slot, d ->
                val docked = state[slots?.keys?.getOrNull(slot)]
                val description = stringResource(R.string.dock_slot_app, slot + 1, docked?.label ?: stringResource(d.label))
                Box(
                    Modifier
                        .size(Dimen.target)
                        .glass(CircleShape)
                        .clickable(role = Role.Button) { onPick(slot) }
                        .semantics { contentDescription = description },
                    contentAlignment = Alignment.Center,
                ) {
                    if (docked != null) AppIcon(docked, apps, Modifier.size(Dimen.glyphTile))
                    else Image(Lucide.icon(d.icon, 20f, tune.strokeDp), contentDescription = null, Modifier.size(Dimen.glyph))
                }
            }
        }
    }
}
