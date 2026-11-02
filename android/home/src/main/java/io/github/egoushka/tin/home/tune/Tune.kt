package io.github.egoushka.tin.home.tune

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.egoushka.tin.home.theme.Attention
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class Preset { Default, Bedside, Battery, DeepWork, Custom }

/** One knob, one variable; the whole Home reads this. Ranges from docs/design/night-glass/cards/Tune.md. */
data class Tune(
    val attention: Attention = Attention.Violet,
    val roomLight: Float = 1f,     // 0..1.4
    val frostDp: Float = 20f,      // 0..32; 0 = no RenderEffect
    val glassTint: Float = 0.55f,  // 0.35..0.85; 0.35 is the contrast floor
    val gapDp: Float = 10f,        // 8 / 10 / 14; card padding follows
    val strokeDp: Float = 1.6f,    // 1.25 / 1.6 / 2
    val focus: Boolean = false,
    val preset: Preset = Preset.Default,
) {
    val padDp: Float get() = gapDp + 2f
    val usesFrost: Boolean get() = frostDp > 0f

    /** A change by hand clears the preset. */
    fun edit(change: Tune.() -> Tune): Tune = change(this).copy(preset = Preset.Custom).normalized()

    fun normalized(): Tune = copy(
        roomLight = roomLight.coerceIn(0f, 1.4f),
        frostDp = frostDp.coerceIn(0f, 32f),
        glassTint = glassTint.coerceIn(0.35f, 0.85f),
        gapDp = GAPS.minBy { kotlin.math.abs(it - gapDp) },
        strokeDp = STROKES.minBy { kotlin.math.abs(it - strokeDp) },
    )

    /** How many knobs differ from Default, shown under the panel. */
    fun changedFromDefault(): Int {
        val d = Tune()
        return listOf(attention != d.attention, roomLight != d.roomLight, frostDp != d.frostDp,
            glassTint != d.glassTint, gapDp != d.gapDp, strokeDp != d.strokeDp, focus != d.focus).count { it }
    }

    companion object {
        val GAPS = listOf(8f, 10f, 14f)
        val STROKES = listOf(1.25f, 1.6f, 2f)

        fun preset(p: Preset): Tune = when (p) {
            Preset.Default, Preset.Custom -> Tune()
            Preset.Bedside -> Tune(roomLight = 0.4f, glassTint = 0.7f, preset = p)
            Preset.Battery -> Tune(frostDp = 0f, roomLight = 0.6f, preset = p)
            Preset.DeepWork -> Tune(focus = true, gapDp = 8f, preset = p)
        }
    }
}

private val Context.tuneStore: DataStore<Preferences> by preferencesDataStore(name = "tin.tune")

class TuneStore(private val context: Context) {
    private object K {
        val attention = stringPreferencesKey("attention")
        val roomLight = floatPreferencesKey("roomLight")
        val frostDp = floatPreferencesKey("frostDp")
        val glassTint = floatPreferencesKey("glassTint")
        val gapDp = floatPreferencesKey("gapDp")
        val strokeDp = floatPreferencesKey("strokeDp")
        val focus = booleanPreferencesKey("focus")
        val preset = stringPreferencesKey("preset")
    }

    val tune: Flow<Tune> = context.tuneStore.data.map { p ->
        val d = Tune()
        Tune(
            attention = p[K.attention]?.let { runCatching { Attention.valueOf(it) }.getOrNull() } ?: d.attention,
            roomLight = p[K.roomLight] ?: d.roomLight,
            frostDp = p[K.frostDp] ?: d.frostDp,
            glassTint = p[K.glassTint] ?: d.glassTint,
            gapDp = p[K.gapDp] ?: d.gapDp,
            strokeDp = p[K.strokeDp] ?: d.strokeDp,
            focus = p[K.focus] ?: d.focus,
            preset = p[K.preset]?.let { runCatching { Preset.valueOf(it) }.getOrNull() } ?: d.preset,
        ).normalized()
    }

    suspend fun save(t: Tune) {
        context.tuneStore.edit { p ->
            p[K.attention] = t.attention.name
            p[K.roomLight] = t.roomLight
            p[K.frostDp] = t.frostDp
            p[K.glassTint] = t.glassTint
            p[K.gapDp] = t.gapDp
            p[K.strokeDp] = t.strokeDp
            p[K.focus] = t.focus
            p[K.preset] = t.preset.name
        }
    }
}
