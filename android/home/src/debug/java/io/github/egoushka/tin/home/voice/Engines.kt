package io.github.egoushka.tin.home.voice

import android.content.Context
import io.github.egoushka.tin.home.BuildConfig
import kotlinx.coroutines.delay

/**
 * The scripted engine keeps the bar's states deterministic on a device: approve, then a miss, alternating.
 * `-Ptin.voice=real` swaps in tin's speech for the voice checks.
 */
object Engines {
    private var n = 0

    private val scripted = VoiceEngine { show ->
        delay(2_000)
        if (n++ % 2 == 1) return@VoiceEngine null
        show(VoiceState.Thinking)
        delay(1_500)
        "approve the deploy"
    }

    fun default(context: Context): VoiceEngine? = if (BuildConfig.REAL_VOICE) TinVoiceEngine.of(context) else scripted
}
