package io.github.egoushka.tin.speech

import io.github.egoushka.tin.speech.PushToTalk.State
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushToTalkTest {
    private val ptt = PushToTalk(minSeconds = 0.3, maxSeconds = 2.0)

    @Test
    fun `press record release decode`() {
        assertTrue(ptt.press())
        assertEquals(State.Recording, ptt.state)
        assertTrue(ptt.append(FloatArray(SAMPLE_RATE) { 0.25f }))
        val audio = ptt.release()!!
        assertEquals(SAMPLE_RATE, audio.size)
        assertEquals(State.Decoding, ptt.state)
        ptt.decoded()
        assertEquals(State.Idle, ptt.state)
    }

    @Test
    fun `a press while recording or decoding is ignored`() {
        ptt.press()
        assertFalse(ptt.press())
        ptt.append(FloatArray(SAMPLE_RATE))
        ptt.release()
        assertFalse(ptt.press())
        assertEquals(State.Decoding, ptt.state)
    }

    @Test
    fun `a tap shorter than the minimum is dropped`() {
        ptt.press()
        ptt.append(FloatArray((0.29 * SAMPLE_RATE).toInt()))
        assertNull(ptt.release())
        assertEquals(State.Idle, ptt.state)
        assertTrue(ptt.press())
    }

    @Test
    fun `recording stops growing at the maximum and keeps order`() {
        ptt.press()
        val chunk = FloatArray(SAMPLE_RATE / 2) { it.toFloat() }
        repeat(3) { assertTrue(ptt.append(chunk)) }
        assertFalse(ptt.append(chunk))
        assertFalse(ptt.append(chunk))
        val audio = ptt.release()!!
        assertEquals(2 * SAMPLE_RATE, audio.size)
        assertArrayEquals(chunk, audio.copyOfRange(SAMPLE_RATE / 2 * 3, 2 * SAMPLE_RATE), 0f)
    }

    @Test
    fun `append and release do nothing when idle`() {
        assertFalse(ptt.append(FloatArray(10)))
        assertNull(ptt.release())
        ptt.decoded()
        assertEquals(State.Idle, ptt.state)
    }

    @Test
    fun `append honours count`() {
        ptt.press()
        ptt.append(FloatArray(SAMPLE_RATE) { 1f }, count = 6000)
        assertEquals(6000, ptt.release()!!.size)
    }
}
