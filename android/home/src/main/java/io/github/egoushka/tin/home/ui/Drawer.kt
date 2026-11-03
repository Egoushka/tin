package io.github.egoushka.tin.home.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.egoushka.tin.home.Actions
import io.github.egoushka.tin.home.R
import io.github.egoushka.tin.home.apps.AppEntry
import io.github.egoushka.tin.home.apps.AppRepository
import io.github.egoushka.tin.home.search.Contact
import io.github.egoushka.tin.home.search.Hit
import io.github.egoushka.tin.home.search.Results
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.theme.LocalTune
import io.github.egoushka.tin.home.theme.Lucide
import io.github.egoushka.tin.home.theme.Ng
import io.github.egoushka.tin.home.theme.NgType
import io.github.egoushka.tin.home.theme.drawRoom
import io.github.egoushka.tin.home.theme.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * All apps (swipe up) and search (tap the bar): A–Z grid, search field at the bottom. Typing swaps the grid for the
 * results card (apps, people, ask Tin, the web) and Enter opens its top row; [onAsk] hands a line to the voice path.
 */
@Composable
fun Drawer(
    apps: AppRepository,
    actions: Actions,
    open: Boolean,
    focusSearch: Boolean,
    onClose: () -> Unit,
    onTune: () -> Unit,
    onAsk: (String) -> Unit,
) {
    val tune = LocalTune.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val all by apps.list.collectAsState()
    val iconPx = with(LocalDensity.current) { Dimen.glyphTile.roundToPx() * 2 }
    LaunchedEffect(all, iconPx) { apps.warm(iconPx, Ng.ink.toArgb()) }
    var query by remember { mutableStateOf("") }
    val sources = rememberSearchSources(all, open)
    val results = remember(query, sources.apps, sources.shortcutIndex, sources.people, sources.access) {
        Results.of(query, sources.apps, sources.shortcutIndex, sources.people, sources.access)
    }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val grid = rememberLazyGridState()
    // The drawer stays composed while closed; it comes back fresh.
    LaunchedEffect(open) {
        if (open) return@LaunchedEffect
        focusManager.clearFocus()
        keyboard?.hide()
        query = ""
        grid.scrollToItem(0)
    }
    if (open && focusSearch) AfterAttach(Unit) { runCatching { focus.requestFocus() } }

    val pick = LocalDockPick.current
    fun run(hit: Hit) {
        // Choosing an app for the dock: only an app can be picked, anything else waits.
        if (pick != null) {
            if (hit is Hit.App) { keyboard?.hide(); pick.put(hit.app) }
            return
        }
        when (hit) {
            // These two keep the drawer: the dialog and the settings page both come back to it.
            Hit.Allow -> return sources.allow()
            Hit.Off -> return actions.openAppSettings()
            is Hit.App -> apps.launch(hit.app)
            is Hit.Shortcut -> if (!sources.shortcuts.start(hit.shortcut)) {
                Toast.makeText(context, resources.getString(R.string.open_failed, hit.shortcut.label), Toast.LENGTH_SHORT).show()
            }
            is Hit.Person -> actions.start(Intent(Intent.ACTION_VIEW, sources.contacts.uri(hit.contact)), hit.contact.name)
            is Hit.Ask -> onAsk(hit.query)
            is Hit.Web -> actions.webSearch(hit.query)
        }
        keyboard?.hide()
        onClose()
    }

    fun call(person: Contact) {
        scope.launch {
            val number = withContext(Dispatchers.IO) { sources.contacts.phone(person) } ?: return@launch
            actions.start(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)), person.name)
            keyboard?.hide()
            onClose()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRoom(tune.roomLight, size) }
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = Dimen.gutter),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = Dimen.space2), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (pick != null) R.string.choose_app else R.string.all_apps).uppercase(),
                style = NgType.label, modifier = Modifier.weight(1f),
            )
            IconButton("sliders", stringResource(R.string.tune), onTune)
            IconButton("x", stringResource(R.string.close), onClose)
        }

        if (query.isBlank()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = grid,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = Dimen.space2),
                verticalArrangement = Arrangement.spacedBy(tune.gapDp.dp + Dimen.space2),
                horizontalArrangement = Arrangement.spacedBy(tune.gapDp.dp),
            ) {
                items(all, key = { it.key }) { app -> AppTile(app, apps) { run(Hit.App(app)) } }
            }
        } else {
            Box(Modifier.weight(1f).padding(vertical = Dimen.space2), contentAlignment = Alignment.TopCenter) {
                SearchPanel(results, apps, onOpen = ::run, onCall = ::call)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = Dimen.space2)
                .height(Dimen.tile)
                .glass(RoundedCornerShape(Dimen.radiusCard))
                .padding(horizontal = Dimen.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(Lucide.icon("search", 20f, tune.strokeDp), contentDescription = null, Modifier.size(Dimen.glyph))
            Spacer(Modifier.width(Dimen.space3))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text(stringResource(R.string.search_hint), style = NgType.title.copy(color = Ng.ink2))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = NgType.title,
                    cursorBrush = SolidColor(Ng.ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { results.top?.let(::run) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        }
    }
}

@Composable
private fun AppTile(app: AppEntry, apps: AppRepository, onClick: () -> Unit) {
    val menu = LocalAppMenu.current
    val unread by rememberUnread(app.pkg)
    // An unread tile reads as one phrase; a plain one keeps its label as text.
    val description = if (unread) stringResource(R.string.app_unread, app.label) else ""
    val longClickLabel = stringResource(R.string.app_options)
    Column(
        Modifier
            .clip(RoundedCornerShape(Dimen.radiusRow))
            .combinedClickable(role = Role.Button, onLongClickLabel = longClickLabel, onLongClick = { menu.open(app, null) }, onClick = onClick)
            .then(if (unread) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(vertical = Dimen.space1),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(Dimen.tile).glass(CircleShape), contentAlignment = Alignment.Center) {
            AppIcon(app, apps, Modifier.size(Dimen.glyphTile * 1.5f))
            UnreadDot(unread)
        }
        Spacer(Modifier.height(Dimen.space1))
        Text(app.label, style = NgType.body.copy(color = Ng.ink), maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().then(if (unread) Modifier.clearAndSetSemantics {} else Modifier))
    }
}

@Composable
fun IconButton(icon: String, label: String, onClick: () -> Unit) {
    val tune = LocalTune.current
    Box(
        Modifier
            .size(Dimen.target)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Image(Lucide.icon(icon, 20f, tune.strokeDp), contentDescription = null, Modifier.size(Dimen.glyph))
    }
}
