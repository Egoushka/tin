package io.github.egoushka.tin.speech

/** Every model here takes 16 kHz mono float samples in [-1, 1]. */
const val SAMPLE_RATE = 16_000

/** Turns one utterance into text. */
fun interface Recognizer {
    fun decode(samples: FloatArray): String
}

/** Voice activity detection over a stream of chunks; each finished speech segment comes out of [poll]. */
interface VoiceDetector {
    fun accept(chunk: FloatArray)

    /** The oldest finished segment, or null. */
    fun poll(): FloatArray?

    /** Ends the open segment, if any, so [poll] returns it. */
    fun flush()

    fun reset()
}

/** The mic, a file, a test fixture. Fills [buffer] and returns how many samples it wrote, or -1 at the end. */
fun interface AudioSource {
    fun read(buffer: FloatArray): Int
}

/** What one recognition produced. */
data class Heard(val text: String, val audioSeconds: Double, val decodeMillis: Long)
