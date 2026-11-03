package io.github.egoushka.tin.home.layout

import android.content.ComponentName
import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.egoushka.tin.home.R
import io.github.egoushka.tin.home.apps.AppEntry
import io.github.egoushka.tin.home.apps.AppRepository
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.theme.Ng
import io.github.egoushka.tin.home.theme.NgType
import io.github.egoushka.tin.home.theme.glass
import io.github.egoushka.tin.home.ui.IconButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.Dp

/** Every launchable app, kept current as packages come and go. */
@Composable
fun rememberInstalledApps(apps: AppRepository): List<AppEntry> =
    produceState(emptyList<AppEntry>(), apps) {
        apps.changes().collect { value = withContext(Dispatchers.IO) { apps.load() } }
    }.value

/** The apps pinned above the dock: themed icons like the drawer's, tap to open, long-press to unpin. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PinsRow(keys: List<String>?, apps: AppRepository, gap: Dp, onUnpin: (String) -> Unit) {
    val installed = rememberInstalledApps(apps)
    val pinned = remember(keys, installed) { PinStore.resolve(keys.orEmpty(), installed) }
    if (pinned.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        pinned.forEach { app ->
            key(app.key) {
                val unpin = stringResource(R.string.unpin_app, app.label)
                Box(
                    Modifier
                        .size(Dimen.tile)
                        .glass(CircleShape)
                        .combinedClickable(
                            role = Role.Button,
                            onLongClickLabel = unpin,
                            onLongClick = { onUnpin(app.key) },
                            onClick = { apps.launch(app) },
                        )
                        .semantics { contentDescription = app.label },
                    contentAlignment = Alignment.Center,
                ) {
                    val icon = appIcon(app, apps)
                    if (icon != null) Image(icon, contentDescription = null, Modifier.size(Dimen.glyphTile * 1.5f))
                }
            }
        }
    }
}

@Composable
private fun appIcon(app: AppEntry, apps: AppRepository): ImageBitmap? {
    val px = with(LocalDensity.current) { Dimen.glyphTile.roundToPx() * 2 }
    return produceState<ImageBitmap?>(null, app.key, px) {
        value = withContext(Dispatchers.IO) { apps.icon(app, px, Ng.ink.toArgb())?.let(Bitmap::asImageBitmap) }
    }.value
}

/** The widgets installed on the phone, by app; a tap starts Android's bind and configure flow. */
@Composable
fun WidgetPicker(host: WidgetHost, onPick: (ComponentName) -> Unit, onClose: () -> Unit) {
    val providers by produceState<List<WidgetProvider>?>(null) { value = withContext(Dispatchers.IO) { host.providers() } }
    PickerSheet(stringResource(R.string.edit_add_widget), onClose) {
        val list = providers
        when {
            list == null -> Unit
            list.isEmpty() -> Text(stringResource(R.string.widget_none), style = NgType.body, modifier = Modifier.padding(Dimen.space4))
            else -> LazyColumn(Modifier.fillMaxWidth()) {
                items(list, key = { it.component.flattenToShortString() }) { p ->
                    PickerRow(p.label, p.app) { onPick(p.component); onClose() }
                }
            }
        }
    }
}

/** Apps not yet pinned, A–Z. */
@Composable
fun AppPicker(apps: AppRepository, pinned: List<String>, onPick: (AppEntry) -> Unit, onClose: () -> Unit) {
    val installed = rememberInstalledApps(apps)
    val free = remember(installed, pinned) { installed.filter { it.key !in pinned } }
    PickerSheet(stringResource(R.string.edit_add_app), onClose) {
        when {
            pinned.size >= PinStore.MAX -> Text(stringResource(R.string.pins_full), style = NgType.body, modifier = Modifier.padding(Dimen.space4))
            free.isEmpty() && installed.isNotEmpty() -> Text(stringResource(R.string.pins_none), style = NgType.body, modifier = Modifier.padding(Dimen.space4))
            else -> LazyColumn(Modifier.fillMaxWidth()) {
                items(free, key = { it.key }) { app -> PickerRow(app.label, null) { onPick(app); onClose() } }
            }
        }
    }
}

@Composable
private fun PickerRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimen.target)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Dimen.space2, vertical = Dimen.space1),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = NgType.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, style = NgType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A bottom sheet over Home, the way Tune is: tap outside to close. */
@Composable
private fun PickerSheet(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Ng.well)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(Dimen.gutter)
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .glass(RoundedCornerShape(Dimen.radiusCard))
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(Dimen.space3),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = NgType.title, modifier = Modifier.weight(1f).padding(start = Dimen.space2))
                IconButton("x", stringResource(R.string.close), onClose)
            }
            content()
        }
    }
}
