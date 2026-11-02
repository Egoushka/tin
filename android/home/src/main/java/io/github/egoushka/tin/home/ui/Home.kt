package io.github.egoushka.tin.home.ui

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import io.github.egoushka.tin.home.voice.Voice
import io.github.egoushka.tin.home.voice.VoiceState
import io.github.egoushka.tin.home.voice.ask
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.egoushka.tin.home.Actions
import io.github.egoushka.tin.home.Dock
import io.github.egoushka.tin.home.R
import io.github.egoushka.tin.home.apps.AppRepository
import io.github.egoushka.tin.home.signals.Signals
import io.github.egoushka.tin.home.signals.WeatherSource
import io.github.egoushka.tin.home.theme.Dimen
import io.github.egoushka.tin.home.theme.Frost
import io.github.egoushka.tin.home.theme.FrostRenderer
import io.github.egoushka.tin.home.theme.Lucide
import io.github.egoushka.tin.home.theme.Motion
import io.github.egoushka.tin.home.theme.Ng
import io.github.egoushka.tin.home.theme.NgType
import io.github.egoushka.tin.home.theme.drawRoom
import io.github.egoushka.tin.home.theme.glass
import io.github.egoushka.tin.home.tune.Tune
import java.util.Date
import kotlin.math.abs
import kotlinx.coroutines.delay

enum class Layer { Home, Apps, Search, Tune }

@Composable
fun HomeScreen(
    tune: Tune,
    frost: Frost,
    layer: Layer,
    onLayer: (Layer) -> Unit,
    onTune: (Tune) -> Unit,
    apps: AppRepository,
    actions: Actions,
    signals: Signals,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scale = animatorScale()
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .drawBehind { drawRoom(tune.roomLight, this.size) },
    ) {
        FrostRenderer(tune, size, frost)
        Home(tune, onLayer, actions, signals)

        // Composed from the first frames on, so opening is a fade and a slide on the render thread and nothing else.
        val open = layer == Layer.Apps || layer == Layer.Search
        var drawerReady by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            withFrameNanos { }
            drawerReady = true
        }
        LaunchedEffect(apps) { apps.keepLoaded() }
        if (drawerReady || open) {
            DrawerHost(open, scale) {
                Drawer(
                    apps = apps,
                    actions = actions,
                    open = open,
                    focusSearch = layer == Layer.Search,
                    onClose = { onLayer(Layer.Home) },
                    onTune = { onLayer(Layer.Tune) },
                    onAsk = { said -> actions.ask(said, signals.needsYou.state.value.needs) },
                )
            }
        }

        AnimatedVisibility(
            visible = layer == Layer.Tune,
            enter = fadeIn(tween(Motion.duration(Motion.NEEDS_YOU_IN, scale))),
            exit = fadeOut(tween(Motion.duration(Motion.BROKEN_IN, scale))),
        ) {
            TuneSheet(tune = tune, onChange = onTune, onClose = { onLayer(Layer.Home) })
        }
    }
}

/** Slides and fades [content] in and out with the layer motion; closed, it is measured but not placed or hit. */
@Composable
private fun DrawerHost(open: Boolean, scale: Float, content: @Composable () -> Unit) {
    val shown by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(Motion.duration(if (open) Motion.NEEDS_YOU_IN else Motion.BROKEN_IN, scale)),
        label = "drawer",
    )
    val isOpen by rememberUpdatedState(open)
    val placed by remember { derivedStateOf { isOpen || shown > 0f } }
    Box(
        Modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                layout(p.width, p.height) { if (placed) p.place(0, 0) }
            }
            .graphicsLayer {
                alpha = shown
                translationY = (1f - shown) * size.height / 3f
            },
    ) { content() }
}

/** The stock launcher's job: glance, dock, search bar; gestures on empty space only. */
@Composable
private fun Home(tune: Tune, onLayer: (Layer) -> Unit, actions: Actions, signals: Signals) {
    val density = LocalDensity.current
    val edge = with(density) { Dimen.backEdge.toPx() }
    val travel = with(density) { Dimen.target.toPx() } // one target's travel opens a layer
    val voice = remember { Voice() }
    val scope = rememberCoroutineScope()
    val listen = {
        val started = voice.start(scope) { said -> actions.ask(said, signals.needsYou.state.value.needs) }
        if (!started && !actions.voice()) onLayer(Layer.Search)
    }
    Column(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var dy = 0f
                var live = false
                detectVerticalDragGestures(
                    onDragStart = { dy = 0f; live = it.x > edge && it.x < size.width - edge },
                    onDragEnd = {
                        if (live && abs(dy) >= travel) {
                            if (dy < 0) onLayer(Layer.Apps) else actions.openNotifications()
                        }
                    },
                    onVerticalDrag = { _, d -> dy += d },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { if (it.x > edge && it.x < size.width - edge) actions.toggleTorch() },
                    onLongPress = { if (it.x > edge && it.x < size.width - edge) onLayer(Layer.Tune) },
                )
            }
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = Dimen.gutter),
    ) {
        Glance(signals.weather, Modifier.padding(top = Dimen.space6))
        Spacer(Modifier.height(tune.gapDp.dp + Dimen.space2))
        Column(verticalArrangement = Arrangement.spacedBy(tune.gapDp.dp)) {
            NeedsYouCard(signals.needsYou, actions)
            TodayCard(signals.calendar, actions)
            GlanceCard(signals, actions)
            MusicCard(signals.music)
            AlarmCard(signals.alarm)
            HomelabCard(signals.homelab)
        }
        Spacer(Modifier.weight(1f))
        DockRow(tune, actions)
        Spacer(Modifier.height(tune.gapDp.dp + Dimen.space2))
        SearchBar(tune, voice.state, onOpen = { onLayer(Layer.Search) }, onMic = listen)
        Spacer(Modifier.height(Dimen.space2))
    }
}

@Composable
private fun Glance(weather: WeatherSource, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000 - value % 60_000) // wake on the minute
        }
    }
    val timePattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    val datePattern = DateFormat.getBestDateTimePattern(locale, stringResource(R.string.date_skeleton))
    val date = Date(now)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(DateFormat.format(timePattern, date).toString(), style = NgType.clock, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                android.icu.text.SimpleDateFormat(datePattern, locale).format(date),
                style = NgType.title, textAlign = TextAlign.End,
            )
            WeatherLine(weather, now)
        }
    }
}

@Composable
private fun DockRow(tune: Tune, actions: Actions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Dock.entries.forEach { d ->
            val label = stringResource(d.label)
            Box(
                Modifier
                    .size(Dimen.tile)
                    .glass(CircleShape)
                    .clickable(role = Role.Button) { actions.dock(d) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Image(Lucide.icon(d.icon, 24f, tune.strokeDp), contentDescription = null, Modifier.size(Dimen.glyphTile))
            }
        }
    }
}

@Composable
private fun SearchBar(tune: Tune, voiceState: StateFlow<VoiceState>, onOpen: () -> Unit, onMic: () -> Unit) {
    val state by voiceState.collectAsState()
    val scale = animatorScale()
    val word = when (state) {
        VoiceState.Idle -> stringResource(R.string.mic)
        VoiceState.Listening -> stringResource(R.string.voice_listening)
        VoiceState.Thinking -> stringResource(R.string.voice_thinking)
        VoiceState.Error -> stringResource(R.string.voice_error)
    }
    // Shape and speed carry the state; colour only marks an error (cards/VoiceStates.md).
    val ink = if (state == VoiceState.Error) Ng.bad else Ng.ink2
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimen.tile)
            .glass(RoundedCornerShape(Dimen.radiusCard))
            .combinedClickable(onClick = onOpen, onLongClick = onMic)
            .padding(start = Dimen.space4, end = Dimen.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(Lucide.icon("search", 20f, tune.strokeDp), contentDescription = null, Modifier.size(Dimen.glyph))
        Spacer(Modifier.width(Dimen.space3))
        Text(
            if (state == VoiceState.Idle) stringResource(R.string.search_hint) else word,
            style = NgType.title.copy(color = ink),
            modifier = Modifier.weight(1f).semantics { if (state != VoiceState.Idle) liveRegion = LiveRegionMode.Polite },
        )
        if (state == VoiceState.Listening) Bars(scale) else if (state == VoiceState.Thinking) Dots(scale)
        Spacer(Modifier.width(Dimen.space2))
        // The mic is white at rest: primary actions carry no colour (Night glass README).
        Box(
            Modifier
                .size(Dimen.target)
                .clip(CircleShape)
                .background(Ng.accent)
                .clickable(role = Role.Button, onClick = onMic)
                .semantics { contentDescription = word },
            contentAlignment = Alignment.Center,
        ) {
            Image(Lucide.icon("mic", 20f, tune.strokeDp, Ng.onAccent), contentDescription = null, Modifier.size(Dimen.glyph))
        }
    }
}

/** Listening: four bars rising and falling out of step. Still, at mid height, with reduced motion. */
@Composable
private fun Bars(scale: Float) {
    val t = rememberInfiniteTransition(label = "bars")
    Row(Modifier.height(Dimen.glyph), horizontalArrangement = Arrangement.spacedBy(Dimen.space1), verticalAlignment = Alignment.CenterVertically) {
        repeat(4) { i ->
            val h by t.animateFloat(
                0.3f, 1f,
                infiniteRepeatable(tween(400 + i * 90, easing = LinearEasing), RepeatMode.Reverse),
                label = "bar$i",
            )
            Box(Modifier.width(3.dp).height(Dimen.glyph * (if (scale == 0f) 0.6f else h)).clip(CircleShape).background(Ng.ink))
        }
    }
}

/** Thinking: three dots lighting one after another (slower than the bars). Still with reduced motion. */
@Composable
private fun Dots(scale: Float) {
    val t = rememberInfiniteTransition(label = "dots")
    val phase by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "phase")
    Row(horizontalArrangement = Arrangement.spacedBy(Dimen.space1), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val lit = scale == 0f || phase.toInt() == i
            Box(Modifier.size(Dimen.space1 + 2.dp).clip(CircleShape).background(Ng.ink.copy(alpha = if (lit) 1f else 0.35f)))
        }
    }
}

/** System animator duration scale; 0 is reduced motion. */
@Composable
fun animatorScale(): Float {
    val resolver = LocalContext.current.contentResolver
    return remember {
        android.provider.Settings.Global.getFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }
}

/** Keeps [key] steady for a frame so a just-opened layer can request focus after it is attached. */
@Composable
fun AfterAttach(key: Any, block: suspend () -> Unit) {
    LaunchedEffect(key) { delay(Motion.FRESH_IN.toLong()); block() }
}
