package io.github.egoushka.tin.home.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.egoushka.tin.home.R
import io.github.egoushka.tin.home.signals.AgendaItem
import io.github.egoushka.tin.home.signals.NeedsYou
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.theme.Ng
import io.github.egoushka.tin.home.theme.NgType
import io.github.egoushka.tin.home.tune.Tune

/** The whole day: every event still to come, not the card's three, then what waits in the approvals app and a way into it. */
@Composable
fun TodaySheet(tune: Tune, agenda: List<AgendaItem>, needs: NeedsYou, onApprovals: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    SheetFrame(tune, onClose) {
        Row(Modifier.padding(start = Dimen.space3), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.today), style = NgType.title, modifier = Modifier.weight(1f))
            IconButton("x", stringResource(R.string.close), onClose)
        }
        if (agenda.isEmpty()) Text(stringResource(R.string.today_empty), style = NgType.body, modifier = Modifier.padding(Dimen.space3))
        agenda.forEach { row ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = Dimen.target).padding(horizontal = Dimen.space3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val begin = DateFormat.getTimeFormat(context).format(java.util.Date(row.event.beginMs))
                Text(begin, style = NgType.body.copy(color = if (row.current) Ng.ink else Ng.ink2), modifier = Modifier.width(Dimen.target + Dimen.space1))
                Text(row.event.title, style = NgType.title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        if (needs.lit) {
            Text(stringResource(R.string.today_queue), style = NgType.label, modifier = Modifier.padding(Dimen.space3))
            needs.rows.forEach { item ->
                Row(Modifier.fillMaxWidth().heightIn(min = Dimen.target).padding(horizontal = Dimen.space3), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, style = NgType.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(item.source, style = NgType.body)
                }
            }
            if (needs.more > 0) Text(stringResource(R.string.needs_more, needs.more), style = NgType.body, modifier = Modifier.padding(horizontal = Dimen.space3))
        }
        SheetRow("arrow-up-right", stringResource(R.string.today_open_approvals), onClick = onApprovals)
    }
}
