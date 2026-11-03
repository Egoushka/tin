package io.github.egoushka.tin.home.voice

import android.content.Context

/** tin's on-device speech. [VoiceEngine.ready] is false when tin is absent, so the mic hands over to the system assistant. */
object Engines {
    fun default(context: Context): VoiceEngine? = TinVoiceEngine.of(context)
}
