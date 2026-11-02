package io.github.egoushka.tin.home.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import io.github.egoushka.tin.home.apps.AppSearch
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.theme.LocalTune
import io.github.egoushka.tin.home.theme.Lucide
import io.github.egoushka.tin.home.theme.Ng
import io.github.egoushka.tin.home.theme.NgType
import io.github.egoushka.tin.home.theme.drawRoom
import io.github.egoushka.tin.home.theme.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** All apps (swipe up) and search (tap the bar): A–Z grid, search field at the bottom, Enter opens the top result. */
@Composable
fun Drawer(apps: AppRepository, actions: Actions, focusSearch: Boolean, onClose: () -> Unit, onTune: () -> Unit) {
    val tune = LocalTune.current
    val all by produceState(emptyList<AppEntry>(), apps) {
        apps.changes().collect { value = withContext(Dispatchers.IO) { apps.load() } }
    }
    var query by remember { mutableStateOf("") }
    val shown = remember(all, query) { AppSearch.filter(all, query) { it.label } }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    if (focusSearch) AfterAttach(Unit) { runCatching { focus.requestFocus() } }

    fun open(app: AppEntry) {
        keyboard?.hide()
        apps.launch(app)
        onClose()
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
            Text(stringResource(R.string.all_apps).uppercase(), style = NgType.label, modifier = Modifier.weight(1f))
            IconButton("sliders", stringResource(R.string.tune), onTune)
            IconButton("x", stringResource(R.string.close), onClose)
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = Dimen.space2),
            verticalArrangement = Arrangement.spacedBy(tune.gapDp.dp + Dimen.space2),
            horizontalArrangement = Arrangement.spacedBy(tune.gapDp.dp),
        ) {
            if (query.isNotBlank()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    WebRow(query) { keyboard?.hide(); actions.webSearch(query.trim()); onClose() }
                }
            }
            if (shown.isEmpty() && all.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.no_apps), style = NgType.body, modifier = Modifier.padding(Dimen.space4))
                }
            }
            items(shown, key = { it.key }) { app -> AppTile(app, apps) { open(app) } }
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
                    keyboardActions = KeyboardActions(onGo = {
                        val top = shown.firstOrNull()
                        if (top != null && query.isNotBlank()) open(top)
                        else if (query.isNotBlank()) { keyboard?.hide(); actions.webSearch(query.trim()); onClose() }
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        }
    }
}

@Composable
private fun AppTile(app: AppEntry, apps: AppRepository, onClick: () -> Unit) {
    val px = with(LocalDensity.current) { Dimen.glyphTile.roundToPx() * 2 }
    val icon by produceState<ImageBitmap?>(null, app.key, px) {
        value = withContext(Dispatchers.IO) { apps.icon(app, px, Ng.ink.toArgb())?.let(Bitmap::asImageBitmap) }
    }
    Column(
        Modifier.clip(RoundedCornerShape(Dimen.radiusRow)).clickable(onClick = onClick).padding(vertical = Dimen.space1),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(Dimen.tile).glass(CircleShape), contentAlignment = Alignment.Center) {
            icon?.let { Image(it, contentDescription = null, Modifier.size(Dimen.glyphTile * 1.5f)) }
        }
        Spacer(Modifier.height(Dimen.space1))
        Text(app.label, style = NgType.body.copy(color = Ng.ink), maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun WebRow(query: String, onClick: () -> Unit) {
    val tune = LocalTune.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimen.target)
            .glass(RoundedCornerShape(Dimen.radiusRow))
            .clickable(onClick = onClick)
            .padding(horizontal = Dimen.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(Lucide.icon("globe", 16f, tune.strokeDp), contentDescription = null, Modifier.size(Dimen.glyphSmall))
        Spacer(Modifier.width(Dimen.space3))
        Text(stringResource(R.string.search_web, query.trim()), style = NgType.title, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
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
