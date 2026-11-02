package io.github.egoushka.tin.speech

/**
 * Parakeet is an offline model, so a stream is recognised the standard sherpa-onnx way: the VAD cuts the audio
 * into speech segments and each segment is decoded whole.
 */
class Listener(
    private val vad: VoiceDetector,
    private val recognizer: Recognizer,
    private val chunkSamples: Int = 512,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    /**
     * One voice command: reads [source] until the first speech segment ends or [maxSeconds] of audio have passed,
     * whichever comes first; a segment still open at the limit is cut there. Silence gives empty text.
     */
    @Synchronized
    fun command(source: AudioSource, maxSeconds: Double): Heard {
        vad.reset()
        val limit = (maxSeconds * SAMPLE_RATE).toLong()
        val buffer = FloatArray(chunkSamples)
        var read = 0L
        var segment: FloatArray? = null
        while (segment == null && read < limit) {
            val n = source.read(buffer)
            if (n <= 0) break
            vad.accept(buffer.copyOf(n))
            read += n
            segment = vad.poll()
        }
        if (segment == null) {
            vad.flush()
            segment = vad.poll()
        }
        vad.reset()
        return segment?.let(::decode) ?: Heard("", 0.0, 0)
    }

    /**
     * A recording or file of any length. Up to [wholeSeconds] it is decoded in one piece (what the benchmark
     * measures); longer audio is split by the VAD and the pieces joined with spaces.
     */
    @Synchronized
    fun transcript(samples: FloatArray, wholeSeconds: Double = 30.0): Heard {
        if (samples.size <= wholeSeconds * SAMPLE_RATE) return decode(samples)
        vad.reset()
        val segments = mutableListOf<FloatArray>()
        var i = 0
        while (i < samples.size) {
            val end = minOf(i + chunkSamples, samples.size)
            vad.accept(samples.copyOfRange(i, end))
            generateSequence { vad.poll() }.forEach(segments::add)
            i = end
        }
        vad.flush()
        generateSequence { vad.poll() }.forEach(segments::add)
        vad.reset()
        val parts = segments.map(::decode)
        return Heard(
            parts.map { it.text }.filter { it.isNotEmpty() }.joinToString(" "),
            parts.sumOf { it.audioSeconds },
            parts.sumOf { it.decodeMillis },
        )
    }

    private fun decode(samples: FloatArray): Heard {
        val start = nanoTime()
        val text = recognizer.decode(samples).trim()
        return Heard(text, samples.size.toDouble() / SAMPLE_RATE, (nanoTime() - start) / 1_000_000)
    }
}
