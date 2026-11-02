package io.github.egoushka.tin.home.voice

import kotlinx.coroutines.delay

/** Stand-in for the speech engine so the bar's states can be driven on a device: approve, then a miss, alternating. */
object Engines {
    private var n = 0

    fun default(): VoiceEngine = VoiceEngine { show ->
        delay(2_000)
        if (n++ % 2 == 1) return@VoiceEngine null
        show(VoiceState.Thinking)
        delay(1_500)
        "approve the deploy"
    }
}
