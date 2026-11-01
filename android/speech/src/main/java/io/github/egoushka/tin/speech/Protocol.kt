package io.github.egoushka.tin.speech

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One request line from tin's `speech__*` tools. */
@Serializable
data class Request(
    val op: String,
    val seconds: Double? = null,
    val file: String? = null,
    @SerialName("since_id") val sinceId: Int? = null,
)

@Serializable
data class Response(
    val ok: Boolean,
    val text: String? = null,
    @SerialName("audio_s") val audioSeconds: Double? = null,
    @SerialName("decode_ms") val decodeMillis: Long? = null,
    val items: List<Transcript>? = null,
    val error: String? = null,
) {
    companion object {
        fun error(message: String) = Response(ok = false, error = message)

        fun heard(h: Heard) = Response(ok = true, text = h.text, audioSeconds = h.audioSeconds, decodeMillis = h.decodeMillis)
    }
}

/** Newline-delimited JSON: one request per line in, one response per line out. */
object Protocol {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun parse(line: String): Request = json.decodeFromString(Request.serializer(), line)

    fun encode(response: Response): String = json.encodeToString(Response.serializer(), response)
}
