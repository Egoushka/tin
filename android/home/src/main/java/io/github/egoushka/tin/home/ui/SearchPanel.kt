package io.github.egoushka.tin.home.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.egoushka.tin.home.R
import io.github.egoushka.tin.home.apps.AppRepository
import io.github.egoushka.tin.home.search.Contact
import io.github.egoushka.tin.home.search.Hit
import io.github.egoushka.tin.home.search.Results
import io.github.egoushka.tin.home.search.Section
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.theme.LocalTune
import io.github.egoushka.tin.home.theme.Lucide
import io.github.egoushka.tin.home.theme.Ng
import io.github.egoushka.tin.home.theme.NgType
import io.github.egoushka.tin.home.theme.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The typed-search card: apps, people, then Ask Tin and the web. The row Enter opens carries the surface-2 fill. */
@Composable
fun SearchPanel(
    results: Results,
    apps: AppRepository,
    onOpen: (Hit) -> Unit,
    onCall: (Contact) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(Dimen.radiusCard))
            .verticalScroll(rememberScrollState())
            .padding(Dimen.space3),
    ) {
        results.groups.forEachIndexed { i, group ->
            Text(
                stringResource(
                    when (group.section) {
                        Section.Apps -> R.string.section_apps
                        Section.People -> R.string.section_people
                        Section.AskOrSearch -> R.string.section_ask
                    },
                ).uppercase(),
                style = NgType.label,
                modifier = Modifier.padding(start = Dimen.space2, top = if (i == 0) Dimen.space1 else Dimen.space3, bottom = Dimen.space1),
            )
            group.hits.forEach { hit ->
                HitRow(hit, apps, isTop = hit.key == results.top?.key, onOpen = { onOpen(hit) }, onCall = onCall)
            }
        }
    }
}

@Composable
private fun HitRow(hit: Hit, apps: AppRepository, isTop: Boolean, onOpen: () -> Unit, onCall: (Contact) -> Unit) {
    val tune = LocalTune.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimen.target)
            .clip(RoundedCornerShape(Dimen.radiusRow))
            .background(if (isTop) Ng.surface2 else Color.Transparent)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = Dimen.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Badge {
            when (hit) {
                is Hit.App -> AppGlyph(hit, apps)
                is Hit.Shortcut -> Glyph("grid", tune.strokeDp)
                is Hit.Person -> Text(hit.contact.name.initial(), style = NgType.body.copy(color = Ng.ink))
                Hit.Allow, Hit.Off -> Glyph("user", tune.strokeDp)
                is Hit.Ask -> Glyph("ask", tune.strokeDp)
                is Hit.Web -> Glyph("globe", tune.strokeDp)
            }
        }
        Spacer(Modifier.width(Dimen.space3))
        Column(Modifier.weight(1f).padding(vertical = Dimen.space1)) {
            Text(title(hit), style = NgType.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            hint(hit)?.let { Text(it, style = NgType.body, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (isTop) {
            Text(
                stringResource(if (hit is Hit.Ask) R.string.enter_ask else R.string.enter_open),
                style = NgType.body,
                modifier = Modifier.padding(start = Dimen.space2),
            )
        }
        if (hit is Hit.Person && hit.contact.hasPhone) {
            val label = stringResource(R.string.call_person, hit.contact.name)
            Box(
                Modifier
                    .size(Dimen.target)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { onCall(hit.contact) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) { Glyph("phone", tune.strokeDp) }
        }
    }
}

@Composable
private fun title(hit: Hit): String = when (hit) {
    is Hit.App -> hit.app.label
    is Hit.Shortcut -> hit.shortcut.label
    is Hit.Person -> hit.contact.name
    Hit.Allow -> stringResource(R.string.contacts_allow)
    Hit.Off -> stringResource(R.string.contacts_off)
    is Hit.Ask -> stringResource(R.string.search_ask, hit.query)
    is Hit.Web -> stringResource(R.string.search_web, hit.query)
}

@Composable
private fun hint(hit: Hit): String? = when (hit) {
    Hit.Allow -> stringResource(R.string.contacts_allow_hint)
    Hit.Off -> stringResource(R.string.contacts_off_hint)
    else -> null
}

/** The 40 dp glass circle that sits in a 48 dp row, so the row's radius stays concentric with the panel's. */
@Composable
private fun Badge(content: @Composable () -> Unit) {
    Box(Modifier.size(Dimen.target - Dimen.space2).clip(CircleShape).background(Ng.surface2), contentAlignment = Alignment.Center) {
        content()
    }
}

@Composable
private fun Glyph(name: String, strokeDp: Float) {
    Image(Lucide.icon(name, 20f, strokeDp), contentDescription = null, Modifier.size(Dimen.glyph))
}

@Composable
private fun AppGlyph(hit: Hit.App, apps: AppRepository) {
    val px = with(LocalDensity.current) { Dimen.glyphTile.roundToPx() * 2 }
    val cached = remember(hit.app.key, px) { apps.cachedIcon(hit.app, px)?.asImageBitmap() }
    val icon by produceState(cached, hit.app.key, px) {
        if (value == null) value = withContext(Dispatchers.IO) { apps.icon(hit.app, px, Ng.ink.toArgb())?.let(Bitmap::asImageBitmap) }
    }
    icon?.let { Image(it, contentDescription = null, Modifier.size(Dimen.glyphTile)) }
}

private fun String.initial(): String = substring(0, offsetByCodePoints(0, 1)).uppercase()
