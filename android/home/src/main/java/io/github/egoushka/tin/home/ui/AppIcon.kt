package io.github.egoushka.tin.home.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import io.github.egoushka.tin.home.apps.AppEntry
import io.github.egoushka.tin.home.apps.AppRepository
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.theme.Ng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Whether a package has unread notifications. Reads snapshot state, so a tile recomposes only when its own answer changes. */
val LocalUnread = staticCompositionLocalOf<(String) -> Boolean> { { false } }

@Composable
fun rememberUnread(pkg: String?): State<Boolean> {
    val unread = LocalUnread.current
    return remember(pkg, unread) { derivedStateOf { pkg != null && unread(pkg) } }
}

/** The app's themed icon as the drawer draws it (the tile's glass is the caller's); nothing until the bitmap is drawn. */
@Composable
fun AppIcon(app: AppEntry, apps: AppRepository, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { Dimen.glyphTile.roundToPx() * 2 }
    val cached = remember(app.key, app.rev, px) { apps.cachedIcon(app, px)?.asImageBitmap() }
    // A new rev is a new icon: the producer restarts and the old one stays until the new one is drawn.
    val icon by produceState(cached, app.key, app.rev, px) {
        value = cached ?: withContext(Dispatchers.IO) { apps.icon(app, px, Ng.ink.toArgb())?.asImageBitmap() }
    }
    icon?.let { Image(it, contentDescription = null, modifier) }
}

/** Calm has no colour, so the unread dot is `ink`, on the rim of the circle at the top end. */
@Composable
fun BoxScope.UnreadDot(show: Boolean) {
    if (!show) return
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .padding(top = Dimen.space1, end = Dimen.space1)
            .size(Dimen.space2)
            .clip(CircleShape)
            .background(Ng.ink),
    )
}
