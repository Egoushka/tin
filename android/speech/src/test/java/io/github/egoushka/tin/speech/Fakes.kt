package io.github.egoushka.tin.speech

/** Speech is any non-zero sample; a segment ends at the first all-zero chunk after speech. */
class FakeVad : VoiceDetector {
    private val open = mutableListOf<Float>()
    private val done = ArrayDeque<FloatArray>()
    var resets = 0

    override fun accept(chunk: FloatArray) {
        if (chunk.any { it != 0f }) open += chunk.toList() else flush()
    }

    override fun poll(): FloatArray? = done.removeFirstOrNull()

    override fun flush() {
        if (open.isNotEmpty()) done.addLast(open.toFloatArray())
        open.clear()
    }

    override fun reset() {
        open.clear()
        done.clear()
        resets++
    }
}

/** "n=<samples>" for every utterance, so a test sees exactly what was decoded. */
class CountingRecognizer : Recognizer {
    val calls = mutableListOf<Int>()

    override fun decode(samples: FloatArray): String {
        calls += samples.size
        return " n=${samples.size} "
    }
}

/** Plays [samples] in reads of at most the caller's buffer size, then ends. */
class ArraySource(private val samples: FloatArray) : AudioSource {
    var pos = 0

    override fun read(buffer: FloatArray): Int {
        if (pos >= samples.size) return -1
        val n = minOf(buffer.size, samples.size - pos)
        samples.copyInto(buffer, 0, pos, pos + n)
        pos += n
        return n
    }
}

fun audio(vararg parts: Pair<Float, Int>): FloatArray =
    parts.flatMap { (v, n) -> List(n) { v } }.toFloatArray()
