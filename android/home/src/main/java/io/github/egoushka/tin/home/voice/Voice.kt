package io.github.egoushka.tin.home.voice

import io.github.egoushka.tin.home.signals.NeedsYou
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The states of `cards/VoiceStates.md`; the answer sheet waits for an engine that produces answers. */
enum class VoiceState { Idle, Listening, Thinking, Error }

/** Speech to text. [show] reports Thinking once the user stops talking; null means nothing was understood. */
fun interface VoiceEngine {
    suspend fun listen(show: (VoiceState) -> Unit): String?
}

/** What an utterance does. Voice opens things; it never decides them (Night glass README, "Voice limits"). */
sealed interface VoiceIntent {
    data class OpenItem(val deepLink: String) : VoiceIntent
    data object OpenQueue : VoiceIntent
    data class Search(val query: String) : VoiceIntent

    companion object {
        // Stems, matched anywhere in the utterance: "approve the deploy", "підтверди деплой".
        private val DECIDING = listOf(
            "approve", "merge", "confirm", "accept", "deny", "reject", "decline",
            "підтверд", "схвал", "злий", "злити", "прийм", "відхил", "відмов", "дозвол",
        )

        /** A deciding word opens the oldest waiting item (or the queue when none); anything else is a search. */
        fun of(utterance: String, needs: NeedsYou): VoiceIntent {
            val said = utterance.trim().lowercase()
            if (DECIDING.none { said.contains(it) }) return Search(utterance.trim())
            val link = needs.rows.firstOrNull()?.deepLink?.takeIf { it.isNotBlank() }
            return if (link != null) OpenItem(link) else OpenQueue
        }
    }
}

/** Runs one listen at a time and publishes the state the search bar draws. */
class Voice(private val engine: VoiceEngine? = Engines.default()) {
    private val _state = MutableStateFlow(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state

    private var job: Job? = null

    /** False when there is no engine, so the caller falls back to the system assistant. A second tap cancels. */
    fun start(scope: CoroutineScope, onUtterance: (String) -> Unit): Boolean {
        val engine = engine ?: return false
        if (job?.isActive == true) {
            job?.cancel()
            _state.value = VoiceState.Idle
            return true
        }
        job = scope.launch {
            _state.value = VoiceState.Listening
            val said = try {
                engine.listen { _state.value = it }
            } finally {
                if (_state.value != VoiceState.Error) _state.value = VoiceState.Idle
            }
            if (said == null) {
                _state.value = VoiceState.Error
                delay(ERROR_MS)
                _state.value = VoiceState.Idle
            } else {
                _state.value = VoiceState.Idle
                onUtterance(said)
            }
        }
        return true
    }

    companion object {
        const val ERROR_MS = 3_000L // starting value, long enough to read two words
    }
}
