package io.github.egoushka.tin.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptsTest {
    @Test
    fun `ids grow and only the newest stay`() {
        val t = Transcripts(capacity = 3)
        repeat(5) { t.add(1000L + it, Heard("t$it", 1.0, 10)) }
        assertEquals(listOf(3, 4, 5), t.since(0).map { it.id })
        assertEquals(listOf("t3", "t4"), t.since(3).map { it.text })
        assertEquals(emptyList<Transcript>(), t.since(5))
    }
}
