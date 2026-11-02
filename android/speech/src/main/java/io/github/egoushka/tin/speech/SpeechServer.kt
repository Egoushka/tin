package io.github.egoushka.tin.speech

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Answers protocol lines. The mic is one resource: a voice command and a push-to-talk recording never overlap, and
 * whoever holds [mic] owns it.
 */
class SpeechServer(
    private val listener: () -> Listener?,
    private val mic: () -> AudioSource,
    private val benchDir: File,
    private val transcripts: Transcripts,
    val micBusy: AtomicBoolean = AtomicBoolean(false),
) {
    fun handle(line: String): String = Protocol.encode(
        try {
            respond(Protocol.parse(line))
        } catch (e: Exception) {
            Response.error("${e.javaClass.simpleName}: ${e.message}")
        },
    )

    private fun respond(r: Request): Response = when (r.op) {
        "transcribe" -> transcribe(r)
        "transcripts" -> Response(ok = true, items = transcripts.since(r.sinceId ?: 0))
        else -> Response.error("unknown op: ${r.op}")
    }

    private fun transcribe(r: Request): Response {
        val seconds = r.seconds ?: DEFAULT_SECONDS
        if (seconds !in 1.0..MAX_SECONDS) return Response.error("seconds must be 1 to ${MAX_SECONDS.toInt()}")
        val engine = listener() ?: return Response.error("speech model not loaded; open tin on the phone")
        val file = r.file
        if (file != null) {
            if (!FILE_NAME.matches(file)) return Response.error("file must be a bare .wav name in the bench folder")
            val f = File(benchDir, file)
            if (!f.isFile) return Response.error("no such file: $file")
            return Response.heard(engine.transcript(Wav.read(f.readBytes())))
        }
        if (!micBusy.compareAndSet(false, true)) return Response.error("mic busy (push-to-talk or another command)")
        return try {
            val source = mic()
            try {
                Response.heard(engine.command(source, seconds))
            } finally {
                (source as? AutoCloseable)?.close()
            }
        } finally {
            micBusy.set(false)
        }
    }

    companion object {
        const val DEFAULT_SECONDS = 8.0
        const val MAX_SECONDS = 60.0
        val FILE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.wav$")
    }
}
