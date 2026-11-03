package io.github.egoushka.tin.home.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.theme.LocalTune
import io.github.egoushka.tin.home.theme.Lucide
import io.github.egoushka.tin.home.theme.Ng
import io.github.egoushka.tin.home.theme.NgType
import io.github.egoushka.tin.home.theme.glass
import io.github.egoushka.tin.home.tune.Tune

/** The glass sheet of Tune, for the app menu and the hidden list: a tap on the scrim closes it. */
@Composable
fun SheetFrame(tune: Tune, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
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
                .glass(RoundedCornerShape(Dimen.radiusCard))
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .verticalScroll(rememberScrollState())
                .padding(tune.padDp.dp + Dimen.space1),
            verticalArrangement = Arrangement.spacedBy(Dimen.space1),
            content = content,
        )
    }
}

/** One action: a 16 dp Lucide glyph and a label on a 48 dp target, with an optional control of its own at the end. */
@Composable
fun SheetRow(icon: String, label: String, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val tune = LocalTune.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimen.target)
            .clip(RoundedCornerShape(Dimen.radiusRow))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Dimen.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(Lucide.icon(icon, 16f, tune.strokeDp), contentDescription = null, Modifier.size(Dimen.glyphSmall))
        Spacer(Modifier.width(Dimen.space3))
        Text(
            label, style = NgType.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = if (trailing != null) Modifier.weight(1f) else Modifier,
        )
        trailing?.invoke()
    }
}
