package io.github.egoushka.tin.home.voice

import io.github.egoushka.tin.home.Actions
import io.github.egoushka.tin.home.signals.NeedsYou

/** Runs a line the way voice does, so a typed "Ask Tin" and a spoken one cannot drift apart. */
fun Actions.ask(said: String, needs: NeedsYou) = ask(said, needs, this::openItem, this::openApprovals, this::webSearch)

internal fun ask(said: String, needs: NeedsYou, openItem: (String) -> Unit, openQueue: () -> Unit, webSearch: (String) -> Unit) {
    when (val intent = VoiceIntent.of(said, needs)) {
        is VoiceIntent.OpenItem -> openItem(intent.deepLink)
        VoiceIntent.OpenQueue -> openQueue()
        is VoiceIntent.Search -> webSearch(intent.query)
    }
}
