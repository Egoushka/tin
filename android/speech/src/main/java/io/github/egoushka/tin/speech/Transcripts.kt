package io.github.egoushka.tin.speech

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Transcript(
    val id: Int,
    @SerialName("started_at_ms") val startedAtMillis: Long,
    @SerialName("audio_s") val audioSeconds: Double,
    @SerialName("decode_ms") val decodeMillis: Long,
    val text: String,
)

/** The last [capacity] push-to-talk results, with ids that only grow, so a reader asks for what is newer. */
class Transcripts(private val capacity: Int = 20) {
    private val items = ArrayDeque<Transcript>()
    private var nextId = 1

    @Synchronized
    fun add(startedAtMillis: Long, heard: Heard): Transcript {
        val t = Transcript(nextId++, startedAtMillis, heard.audioSeconds, heard.decodeMillis, heard.text)
        items.addLast(t)
        while (items.size > capacity) items.removeFirst()
        return t
    }

    @Synchronized
    fun since(id: Int): List<Transcript> = items.filter { it.id > id }
}
