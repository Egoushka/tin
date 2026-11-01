package io.github.egoushka.tin.speech

/**
 * Push-to-talk as a toggle (a tile or notification button): idle → recording → decoding → idle. A press while
 * decoding is ignored, and a recording shorter than [minSeconds] is dropped as an accidental tap.
 */
class PushToTalk(private val minSeconds: Double = 0.3, maxSeconds: Double = 120.0) {
    enum class State { Idle, Recording, Decoding }

    private val maxSamples = (maxSeconds * SAMPLE_RATE).toInt()
    private var buffer = FloatArray(0)
    private var size = 0

    @get:Synchronized
    var state = State.Idle
        private set

    /** Starts a recording; false when one is already running or still decoding. */
    @Synchronized
    fun press(): Boolean {
        if (state != State.Idle) return false
        state = State.Recording
        buffer = FloatArray(SAMPLE_RATE * 10)
        size = 0
        return true
    }

    /** Adds mic samples. Returns false once the recording is full (or not recording): the caller stops. */
    @Synchronized
    fun append(samples: FloatArray, count: Int = samples.size): Boolean {
        if (state != State.Recording) return false
        val n = minOf(count, maxSamples - size)
        if (size + n > buffer.size) buffer = buffer.copyOf(minOf(maxOf(buffer.size * 2, size + n), maxSamples))
        samples.copyInto(buffer, size, 0, n)
        size += n
        return size < maxSamples
    }

    /** Ends the recording. Returns the audio to decode (state Decoding), or null if it was too short (state Idle). */
    @Synchronized
    fun release(): FloatArray? {
        if (state != State.Recording) return null
        val audio = buffer.copyOf(size)
        buffer = FloatArray(0)
        size = 0
        if (audio.size < minSeconds * SAMPLE_RATE) {
            state = State.Idle
            return null
        }
        state = State.Decoding
        return audio
    }

    /** Decoding finished (or failed): ready for the next press. */
    @Synchronized
    fun decoded() {
        if (state == State.Decoding) state = State.Idle
    }
}
