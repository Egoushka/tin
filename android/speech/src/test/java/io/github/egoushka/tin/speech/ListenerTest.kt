package io.github.egoushka.tin.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class ListenerTest {
    private val vad = FakeVad()
    private val rec = CountingRecognizer()
    private var now = 0L
    private val listener = Listener(vad, rec, chunkSamples = 100, nanoTime = { now.also { now += 7_000_000 } })

    @Test
    fun `command stops at the end of the first utterance and leaves the rest unread`() {
        val src = ArraySource(audio(0f to 200, 0.5f to 300, 0f to 100, 0.5f to 500))
        val heard = listener.command(src, maxSeconds = 10.0)
        assertEquals("n=300", heard.text)
        assertEquals(300.0 / SAMPLE_RATE, heard.audioSeconds, 1e-9)
        assertEquals(7L, heard.decodeMillis)
        assertEquals(600, src.pos)
    }

    @Test
    fun `command cuts an utterance still open at the limit`() {
        val src = ArraySource(audio(0.5f to SAMPLE_RATE * 5))
        val heard = listener.command(src, maxSeconds = 1.0)
        assertEquals("n=$SAMPLE_RATE", heard.text)
        assertEquals(SAMPLE_RATE, src.pos)
    }

    @Test
    fun `silence gives empty text without decoding`() {
        val heard = listener.command(ArraySource(audio(0f to SAMPLE_RATE * 2)), maxSeconds = 1.0)
        assertEquals(Heard("", 0.0, 0), heard)
        assertEquals(emptyList<Int>(), rec.calls)
    }

    @Test
    fun `command resets the detector before and after`() {
        listener.command(ArraySource(audio(0.5f to 100, 0f to 100)), maxSeconds = 1.0)
        assertEquals(2, vad.resets)
    }

    @Test
    fun `onSegment runs once, before the segment is decoded`() {
        val decodedBefore = mutableListOf<Int>()
        val src = ArraySource(audio(0.5f to 300, 0f to 100, 0.5f to 300, 0f to 100))
        val heard = listener.command(src, maxSeconds = 10.0, onSegment = { decodedBefore += rec.calls.size })
        assertEquals(listOf(0), decodedBefore)
        assertEquals(listOf(300), rec.calls)
        assertEquals("n=300", heard.text)
    }

    @Test
    fun `cancelled ends the command within one chunk with empty text and no decode`() {
        var checks = 0
        var segments = 0
        val src = ArraySource(audio(0.5f to SAMPLE_RATE * 2))
        val heard = listener.command(
            src,
            maxSeconds = 10.0,
            onSegment = { segments++ },
            cancelled = { checks++ >= 3 },
        )
        assertEquals(Heard("", 0.0, 0), heard)
        assertEquals(300, src.pos)
        assertEquals(emptyList<Int>(), rec.calls)
        assertEquals(0, segments)
        assertEquals(2, vad.resets)
    }

    @Test
    fun `short transcript is decoded whole, silence included`() {
        val heard = listener.transcript(audio(0f to 100, 0.5f to 300, 0f to 100), wholeSeconds = 1.0)
        assertEquals(listOf(500), rec.calls)
        assertEquals("n=500", heard.text)
    }

    @Test
    fun `long transcript is split by the detector and joined`() {
        val samples = audio(0.5f to 300, 0f to 100, 0.5f to 200, 0f to 100, 0.5f to 50)
        val heard = listener.transcript(samples, wholeSeconds = 0.01)
        assertEquals(listOf(300, 200, 50), rec.calls)
        assertEquals("n=300 n=200 n=50", heard.text)
        assertEquals(550.0 / SAMPLE_RATE, heard.audioSeconds, 1e-9)
        assertEquals(21L, heard.decodeMillis)
    }
}
