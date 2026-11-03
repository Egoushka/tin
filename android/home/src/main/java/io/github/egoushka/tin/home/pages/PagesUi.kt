package io.github.egoushka.tin.home.pages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import io.github.egoushka.tin.home.R
import io.github.egoushka.tin.home.apps.AppEntry
import io.github.egoushka.tin.home.apps.AppRepository
import io.github.egoushka.tin.home.apps.AppShortcut
import io.github.egoushka.tin.home.apps.Apps
import io.github.egoushka.tin.home.layout.HomeLayout
import io.github.egoushka.tin.home.layout.HomeState
import io.github.egoushka.tin.home.layout.Pill
import io.github.egoushka.tin.home.layout.StepButton
import io.github.egoushka.tin.home.layout.WidgetCell
import io.github.egoushka.tin.home.layout.ownsLongPress
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.theme.LocalTune
import io.github.egoushka.tin.home.theme.Lucide
import io.github.egoushka.tin.home.theme.Ng
import io.github.egoushka.tin.home.theme.NgType
import io.github.egoushka.tin.home.theme.glass
import io.github.egoushka.tin.home.ui.AppIcon
import io.github.egoushka.tin.home.ui.IconButton
import io.github.egoushka.tin.home.ui.LocalAppMenu
import io.github.egoushka.tin.home.ui.SheetRow
import io.github.egoushka.tin.home.ui.UnreadDot
import io.github.egoushka.tin.home.ui.rememberUnread
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

object PageTags {
    const val Dots = "pages.dots"
    const val Tile = "pages.tile"
    const val Empty = "pages.empty"
}

/** One thing on an extra page: [page] counts the extra pages, so 0 is the one right of the main page. */
data class PagePlace(val page: Int, val id: String)

/** What the app menu can do about pages; [place] is set when the menu was opened from a tile on a page. */
class PageActions(
    val count: Int,
    val place: PagePlace?,
    val canEarlier: Boolean,
    val canLater: Boolean,
    /** A null page is a new one. */
    val addApp: (page: Int?) -> Unit,
    val addShortcut: (shortcut: AppShortcut, page: Int?) -> Unit,
    val move: (to: Int) -> Unit,
    val step: (delta: Int) -> Unit,
    val remove: () -> Unit,
)

/** The menu of [app] as [home] answers it; [place] is where the press was on an extra page, null anywhere else. */
fun pageActionsFor(home: HomeState, app: AppEntry, place: PagePlace?): PageActions {
    val items = place?.let { home.pages.pages.getOrNull(it.page) }.orEmpty()
    val at = if (place == null) -1 else items.indexOfFirst { it.id == place.id }
    fun put(item: PageItem, page: Int?) = if (page == null) home.placeOnNewPage(item) else home.place(page, item)
    return PageActions(
        count = home.pages.count,
        place = place,
        canEarlier = at > 0,
        canLater = at in 0 until items.lastIndex,
        addApp = { page -> put(PageItem.App(app.key), page) },
        addShortcut = { shortcut, page -> put(PageItem.Shortcut(app.pkg, shortcut.id, app.user, shortcut.label), page) },
        move = { to -> if (place != null) home.moveItem(place.page, place.id, to, null) },
        step = { delta -> if (place != null) home.stepItem(place.page, place.id, delta) },
        remove = { if (place != null) items.getOrNull(at)?.let { home.unplace(place.page, it) } },
    )
}

/** The pages to choose from, numbered as the dots count them (the main page is 1), and a new page while there is room. */
@Composable
fun PageChooser(count: Int, exclude: Int?, allowNew: Boolean, onChoose: (page: Int?) -> Unit) {
    Column(Modifier.padding(vertical = Dimen.space1), verticalArrangement = Arrangement.spacedBy(Dimen.space1)) {
        Text(
            stringResource(R.string.page_choose).uppercase(), style = NgType.label,
            modifier = Modifier.padding(horizontal = Dimen.space3),
        )
        repeat(count) { page ->
            if (page != exclude) SheetRow("grid", stringResource(R.string.page_number, page + 2)) { onChoose(page) }
        }
        if (allowNew && count < Pages.MAX_EXTRA) SheetRow("grid", stringResource(R.string.page_new)) { onChoose(null) }
    }
}

/** The dots under the pages, one screen-reader stop that says where you are and can turn the page; edit mode adds "+". */
@Composable
fun PageDots(pager: PagerState, extra: Int, editing: Boolean, onAdd: () -> Unit) {
    val total = 1 + extra
    if (total == 1 && !editing) return
    val scope = rememberCoroutineScope()
    val current = pager.currentPage.coerceIn(0, total - 1)
    val where = stringResource(R.string.page_of, current + 1, total)
    val next = stringResource(R.string.page_next)
    val previous = stringResource(R.string.page_previous)
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimen.target).testTag(PageTags.Dots),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (total > 1) {
            Row(
                Modifier
                    .semantics(mergeDescendants = true) {
                        contentDescription = where
                        customActions = buildList<CustomAccessibilityAction> {
                            if (current < total - 1) add(CustomAccessibilityAction(next) { scope.launch { pager.animateScrollToPage(current + 1) }; true })
                            if (current > 0) add(CustomAccessibilityAction(previous) { scope.launch { pager.animateScrollToPage(current - 1) }; true })
                        }
                    }
                    .padding(horizontal = Dimen.space2),
                horizontalArrangement = Arrangement.spacedBy(Dimen.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(total) { i ->
                    Box(
                        Modifier
                            .size(Dimen.space2)
                            .clip(CircleShape)
                            .background(Ng.ink.copy(alpha = if (i == current) 1f else 0.35f)),
                    )
                }
            }
        }
        if (editing && extra < Pages.MAX_EXTRA) StepButton("+", stringResource(R.string.page_add), onClick = onAdd)
    }
}

private fun Apps.appOf(item: PageItem): AppEntry? = when (item) {
    is PageItem.App -> this[item.key]
    is PageItem.Shortcut -> all.firstOrNull { it.pkg == item.pkg && it.user == item.user }
    is PageItem.Widget -> null
}

/** What one extra page shows: tiles four to a row, a widget on a row of its own. In edit mode it has a header and drag marks. */
@Composable
fun PageContent(page: Int, home: HomeState, apps: AppRepository, drag: PageDrag) {
    val items = home.pages.pages.getOrNull(page) ?: return
    val rows = remember(items) { Pages.flow(items) }
    val editing = home.editing
    val lifted = drag.lifted
    val target = drag.over == page && lifted != null
    val lastId = items.lastOrNull { it.id != lifted?.id }?.id
    if (editing) PageHeader(page, home)
    if (items.isEmpty()) {
        Text(
            stringResource(R.string.page_empty), style = NgType.body.copy(color = Ng.ink2),
            modifier = Modifier.testTag(PageTags.Empty).padding(Dimen.space4),
        )
        return
    }
    rows.forEach { row ->
        when (row) {
            is FlowRow.Tiles -> Row(Modifier.fillMaxWidth()) {
                row.items.forEach { item ->
                    key(item.id) {
                        Box(Modifier.weight(1f)) {
                            PageTile(
                                item, page, items.indexOfFirst { it.id == item.id }, items.size,
                                before = target && drag.before == item.id,
                                after = target && drag.before == null && item.id == lastId,
                                home, apps, drag,
                            )
                        }
                    }
                }
                repeat(Pages.COLUMNS - row.items.size) { Spacer(Modifier.weight(1f)) }
            }
            is FlowRow.Wide -> key(row.widget.id) {
                PageWidget(
                    row.widget, page, items.indexOfFirst { it.id == row.widget.id }, items.size,
                    before = target && drag.before == row.widget.id,
                    after = target && drag.before == null && row.widget.id == lastId,
                    home, drag,
                )
            }
        }
    }
}

/** The page's title and its removal, which asks first because everything on it goes with it. */
@Composable
private fun PageHeader(page: Int, home: HomeState) {
    var asking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Dimen.space1)) {
        Row(Modifier.fillMaxWidth().heightIn(min = Dimen.target), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.page_number, page + 2), style = NgType.label.copy(color = Ng.ink2),
                modifier = Modifier.weight(1f).padding(start = Dimen.space2),
            )
            IconButton("trash-2", stringResource(R.string.page_remove)) { asking = true }
        }
        if (asking) {
            Text(
                stringResource(R.string.page_remove_ask, page + 2), style = NgType.body,
                modifier = Modifier.padding(horizontal = Dimen.space2),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Dimen.space2)) {
                Pill(stringResource(R.string.page_remove_no), Modifier.weight(1f)) { asking = false }
                Pill(stringResource(R.string.page_remove_yes), Modifier.weight(1f), accent = true) {
                    // The next page slides into this slot with this state still here, so it is cleared before the removal.
                    asking = false
                    home.removePage(page)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageTile(
    item: PageItem, page: Int, index: Int, count: Int, before: Boolean, after: Boolean,
    home: HomeState, apps: AppRepository, drag: PageDrag,
) {
    val state by apps.state.collectAsState()
    val app = state.appOf(item)
    if (app == null) {
        // Not resolved (yet, or the app is gone): the tile keeps its place but shows nothing.
        Spacer(Modifier.fillMaxWidth().height(Dimen.tile))
        return
    }
    val menu = LocalAppMenu.current
    val tune = LocalTune.current
    val unread by rememberUnread(app.pkg)
    val shortcut = item as? PageItem.Shortcut
    val label = shortcut?.label?.ifEmpty { app.label } ?: app.label
    val named = if (shortcut != null) stringResource(R.string.page_shortcut_of, label, app.label) else label
    val description = if (unread) stringResource(R.string.app_unread, named) else named
    val options = stringResource(R.string.app_options)
    val earlier = stringResource(R.string.page_earlier)
    val later = stringResource(R.string.page_later)
    val removeName = stringResource(R.string.page_item_remove)
    val moves = (0 until home.pages.count).filter { it != page }.map { it to stringResource(R.string.page_move_to, it + 2) }
    val actions = buildList<CustomAccessibilityAction> {
        if (index > 0) add(CustomAccessibilityAction(earlier) { home.stepItem(page, item.id, -1); true })
        if (index < count - 1) add(CustomAccessibilityAction(later) { home.stepItem(page, item.id, 1); true })
        moves.forEach { (to, name) -> add(CustomAccessibilityAction(name) { home.moveItem(page, item.id, to, null); true }) }
        add(CustomAccessibilityAction(removeName) { home.unplace(page, item); true })
    }
    val menuOpen = { menu.openOnPage(app, PagePlace(page, item.id)) }
    val launch = {
        if (shortcut == null) {
            apps.launch(app)
        } else {
            // A shortcut the app dropped still opens the app.
            apps.startShortcut(app, AppShortcut(shortcut.shortcutId, label)).onFailure { apps.launch(app) }
        }
        Unit
    }
    val faded = drag.lifted?.id == item.id
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(PageTags.Tile)
            .pageSpot(drag, page, item.id)
            .ownsLongPress()
            .graphicsLayer { alpha = if (faded) 0.3f else 1f }
            .dropMark(Ng.accent, before, after, Dimen.space1, across = false)
            .clip(RoundedCornerShape(Dimen.radiusRow))
            // In edit mode a long press lifts the tile, so a tap opens its menu and nothing else listens.
            .then(
                if (home.editing) Modifier.clickable(role = Role.Button, onClick = menuOpen)
                else Modifier.combinedClickable(role = Role.Button, onLongClickLabel = options, onLongClick = menuOpen, onClick = launch),
            )
            .semantics(mergeDescendants = true) {
                contentDescription = description
                customActions = actions
            }
            .padding(vertical = Dimen.space1),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(Dimen.tile).glass(CircleShape), contentAlignment = Alignment.Center) {
            AppIcon(app, apps, Modifier.size(Dimen.glyphTile * 1.5f))
            UnreadDot(unread)
            if (shortcut != null) {
                Image(
                    Lucide.icon("arrow-up-right", 16f, tune.strokeDp), contentDescription = null,
                    Modifier.align(Alignment.BottomEnd).size(Dimen.glyphSmall),
                )
            }
        }
        Spacer(Modifier.height(Dimen.space1))
        Text(
            label, style = NgType.body.copy(color = Ng.ink), maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
        )
    }
}

@Composable
private fun PageWidget(
    widget: PageItem.Widget, page: Int, index: Int, count: Int, before: Boolean, after: Boolean,
    home: HomeState, drag: PageDrag,
) {
    val editing = home.editing
    val id = widget.appWidgetId
    val faded = drag.lifted?.id == widget.id
    Column(
        Modifier
            .fillMaxWidth()
            .pageSpot(drag, page, widget.id)
            .graphicsLayer { alpha = if (faded) 0.3f else 1f }
            .dropMark(Ng.accent, before, after, Dimen.space1, across = true),
        verticalArrangement = Arrangement.spacedBy(Dimen.space1),
    ) {
        if (editing) {
            val name = remember(id) { home.widgets.label(id) } ?: stringResource(R.string.widget_unavailable)
            val range = remember(id) { home.widgets.rowRange(id) ?: 1..HomeLayout.MAX_ROWS }
            val rows = widget.rows.coerceIn(range)
            Row(Modifier.fillMaxWidth().heightIn(min = Dimen.target), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name, style = NgType.label.copy(color = Ng.ink2), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = Dimen.space2),
                )
                StepButton("−", stringResource(R.string.edit_shorter, name), enabled = rows > range.first) { home.resizePageWidget(id, rows - 1) }
                StepButton("+", stringResource(R.string.edit_taller, name), enabled = rows < range.last) { home.resizePageWidget(id, rows + 1) }
                StepButton("↑", stringResource(R.string.edit_up, name), enabled = index > 0) { home.stepItem(page, widget.id, -1) }
                StepButton("↓", stringResource(R.string.edit_down, name), enabled = index < count - 1) { home.stepItem(page, widget.id, 1) }
                IconButton("x", stringResource(R.string.edit_remove, name)) { home.unplace(page, widget) }
            }
        }
        WidgetCell(id, widget.rows, home.widgets, editing, onRemove = { home.unplace(page, widget) }, sideways = true)
    }
}

/** A bar on the edge where a drop would land: the start of the item it goes before, or the end of the last one. */
private fun Modifier.dropMark(color: Color, before: Boolean, after: Boolean, bar: Dp, across: Boolean): Modifier =
    if (!before && !after) this else drawWithContent {
        drawContent()
        val t = bar.toPx()
        val w = size.width
        val h = size.height
        val shape = if (across) Size(w, t) else Size(t, h)
        if (before) drawRect(color, Offset.Zero, shape)
        if (after) drawRect(color, if (across) Offset(0f, h - t) else Offset(w - t, 0f), shape)
    }

/** The lifted item under the finger. */
@Composable
fun DragGhost(drag: PageDrag, home: HomeState, apps: AppRepository) {
    val item = drag.lifted ?: return
    val state by apps.state.collectAsState()
    val wide = item is PageItem.Widget
    val width = if (wide) Dimen.tile * 2 else Dimen.tile
    val halfW = with(LocalDensity.current) { (width / 2).roundToPx() }
    val halfH = with(LocalDensity.current) { (Dimen.tile / 2).roundToPx() }
    Box(
        Modifier
            .offset { IntOffset(drag.pointer.x.roundToInt() - halfW, drag.pointer.y.roundToInt() - halfH) }
            .size(width, Dimen.tile)
            .graphicsLayer { scaleX = 1.1f; scaleY = 1.1f; alpha = 0.9f }
            .glass(if (wide) RoundedCornerShape(Dimen.radiusCard) else CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val app = state.appOf(item)
        if (app != null) {
            AppIcon(app, apps, Modifier.size(Dimen.glyphTile * 1.5f))
        } else if (item is PageItem.Widget) {
            Text(
                remember(item.appWidgetId) { home.widgets.label(item.appWidgetId) } ?: "",
                style = NgType.label.copy(color = Ng.ink), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Dimen.space2),
            )
        }
    }
}
