package io.github.egoushka.tin.home.voice

/** No on-device engine until the speech work (`feat/speech`) lands: the mic hands over to the system assistant. */
object Engines {
    fun default(): VoiceEngine? = null
}
