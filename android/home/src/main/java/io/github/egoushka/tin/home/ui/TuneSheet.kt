package io.github.egoushka.tin.home.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.egoushka.tin.home.R
import io.github.egoushka.tin.home.theme.Attention
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.theme.Ng
import io.github.egoushka.tin.home.theme.NgType
import io.github.egoushka.tin.home.theme.glass
import io.github.egoushka.tin.home.tune.Preset
import io.github.egoushka.tin.home.tune.Tune
import kotlin.math.roundToInt

/** Tune (long press on Home, or the sliders button in All apps): every knob writes `tin.tune` at once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TuneSheet(tune: Tune, onChange: (Tune) -> Unit, onEditHome: () -> Unit, onClose: () -> Unit) {
    val res = LocalContext.current.resources
    val close = stringResource(R.string.close)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // A sibling of the panel, not its parent: a clickable parent merges the panel's text into its own node.
        Box(
            Modifier
                .fillMaxSize()
                .background(Ng.well)
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClose)
                .semantics { contentDescription = close },
        )
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(Dimen.gutter)
                .fillMaxWidth()
                .glass(RoundedCornerShape(Dimen.radiusCard))
                // Takes the tap so it does not fall through to the scrim; unlike clickable it adds no node.
                .pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState())
                .padding(tune.padDp.dp + Dimen.space1),
            verticalArrangement = Arrangement.spacedBy(Dimen.space3),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tune), style = NgType.title, modifier = Modifier.weight(1f))
                IconButton("x", stringResource(R.string.close), onClose)
            }

            Chip(stringResource(R.string.edit_home), selected = false, role = Role.Button, onClick = onEditHome)

            Section(R.string.tune_preset)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimen.space2), verticalArrangement = Arrangement.spacedBy(Dimen.space2)) {
                PRESETS.forEach { (p, label) ->
                    Chip(stringResource(label), tune.preset == p) { onChange(Tune.preset(p).copy(attention = tune.attention)) }
                }
            }

            Section(R.string.tune_attention)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimen.space2), verticalArrangement = Arrangement.spacedBy(Dimen.space2)) {
                ATTENTION.forEach { (a, label) ->
                    Chip(stringResource(label), tune.attention == a, dot = a.color) { onChange(tune.copy(attention = a)) }
                }
            }

            Knob(R.string.tune_room, "${(tune.roomLight * 100).roundToInt()}%", tune.roomLight, 0f..1.4f) {
                onChange(tune.edit { copy(roomLight = it) })
            }
            Knob(R.string.tune_frost, "${tune.frostDp.roundToInt()} dp", tune.frostDp, 0f..32f) {
                onChange(tune.edit { copy(frostDp = it.roundToInt().toFloat()) })
            }
            Knob(R.string.tune_tint, "${(tune.glassTint * 100).roundToInt()}%", tune.glassTint, 0.35f..0.85f) {
                onChange(tune.edit { copy(glassTint = it) })
            }

            Section(R.string.tune_density)
            Row(horizontalArrangement = Arrangement.spacedBy(Dimen.space2)) {
                Tune.GAPS.forEach { g -> Chip(g.roundToInt().toString(), tune.gapDp == g) { onChange(tune.edit { copy(gapDp = g) }) } }
            }
            Section(R.string.tune_stroke)
            Row(horizontalArrangement = Arrangement.spacedBy(Dimen.space2)) {
                Tune.STROKES.forEach { s -> Chip(s.toString(), tune.strokeDp == s) { onChange(tune.edit { copy(strokeDp = s) }) } }
            }

            Row(
                Modifier
                    .heightIn(min = Dimen.target)
                    .toggleable(tune.focus, role = Role.Switch) { onChange(tune.edit { copy(focus = it) }) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.tune_focus), style = NgType.title, modifier = Modifier.weight(1f))
                Switch(
                    checked = tune.focus,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Ng.onAccent, checkedTrackColor = Ng.accent,
                        uncheckedThumbColor = Ng.ink2, uncheckedTrackColor = Ng.track, uncheckedBorderColor = Ng.edge,
                    ),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                val n = tune.changedFromDefault()
                Text(res.getQuantityString(R.plurals.tune_changed, n, n), style = NgType.body, modifier = Modifier.weight(1f))
                Chip(stringResource(R.string.tune_reset), selected = false, role = Role.Button) { onChange(Tune()) }
            }
        }
    }
}

private val PRESETS = listOf(
    Preset.Default to R.string.preset_default, Preset.Bedside to R.string.preset_bedside,
    Preset.Battery to R.string.preset_battery, Preset.DeepWork to R.string.preset_deep,
)
private val ATTENTION = listOf(
    Attention.Violet to R.string.attn_violet, Attention.Blue to R.string.attn_blue,
    Attention.Rose to R.string.attn_rose, Attention.Amber to R.string.attn_amber,
)

@Composable
private fun Section(@StringRes label: Int) {
    Text(stringResource(label).uppercase(), style = NgType.label)
}

/** Selected chips are white (accent): primary state carries no colour. */
@Composable
private fun Chip(label: String, selected: Boolean, dot: Color? = null, role: Role = Role.RadioButton, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Dimen.radiusRow)
    Row(
        Modifier
            .heightIn(min = Dimen.target - Dimen.space2)
            .clip(shape)
            .background(if (selected) Ng.accent else Ng.surface2)
            .border(1.dp, if (selected) Color.Transparent else Ng.edge, shape)
            .then(
                if (role == Role.RadioButton) Modifier.selectable(selected, role = role, onClick = onClick)
                else Modifier.clickable(role = role, onClick = onClick),
            )
            .padding(horizontal = Dimen.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(Dimen.space2 + Dimen.space1).clip(CircleShape).background(dot))
            Spacer(Modifier.width(Dimen.space2))
        }
        Text(label, style = NgType.body.copy(color = if (selected) Ng.onAccent else Ng.ink))
    }
}

@Composable
private fun Knob(@StringRes label: Int, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val name = stringResource(label)
    Column {
        // The slider carries the name and the value; the row is their sighted copy.
        Row(Modifier.clearAndSetSemantics { }) {
            Text(name.uppercase(), style = NgType.label, modifier = Modifier.weight(1f))
            Text(value, style = NgType.label.copy(color = Ng.ink))
        }
        Spacer(Modifier.height(Dimen.space1))
        Slider(
            value = v, onValueChange = onChange, valueRange = range,
            modifier = Modifier.semantics { contentDescription = name; stateDescription = value },
            colors = SliderDefaults.colors(thumbColor = Ng.accent, activeTrackColor = Ng.accent, inactiveTrackColor = Ng.track),
        )
    }
}
