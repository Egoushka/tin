package io.github.egoushka.tin.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    private fun wav(format: Int, bits: Int, rate: Int = SAMPLE_RATE, channels: Int = 1, extra: ByteArray = ByteArray(0), data: (ByteBuffer) -> Unit, dataLen: Int): ByteArray {
        val b = ByteBuffer.allocate(12 + 24 + extra.size + 8 + dataLen + (dataLen and 1)).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(b.capacity() - 8).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(format.toShort()).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        b.put(extra)
        b.put("data".toByteArray()).putInt(dataLen)
        data(b)
        return b.array()
    }

    @Test
    fun `16-bit PCM`() {
        val bytes = wav(1, 16, data = { it.putShort(16384).putShort(-32768) }, dataLen = 4)
        assertArrayEquals(floatArrayOf(0.5f, -1f), Wav.read(bytes), 0f)
    }

    @Test
    fun `32-bit float, after an unknown chunk with odd length`() {
        val odd = ByteBuffer.allocate(8 + 3 + 1).order(ByteOrder.LITTLE_ENDIAN).put("LIST".toByteArray()).putInt(3).array()
        val bytes = wav(3, 32, extra = odd, data = { it.putFloat(0.25f).putFloat(-0.75f) }, dataLen = 8)
        assertArrayEquals(floatArrayOf(0.25f, -0.75f), Wav.read(bytes), 0f)
    }

    @Test
    fun `refuses other rates, stereo and non-WAV`() {
        assertThrows(IllegalArgumentException::class.java) { Wav.read(wav(1, 16, rate = 44100, data = {}, dataLen = 0)) }
        assertThrows(IllegalArgumentException::class.java) { Wav.read(wav(1, 16, channels = 2, data = {}, dataLen = 0)) }
        assertThrows(IllegalArgumentException::class.java) { Wav.read("hello world!".toByteArray()) }
    }

    @Test
    fun `refuses 8-bit`() {
        val e = assertThrows(IllegalArgumentException::class.java) { Wav.read(wav(1, 8, data = { it.put(1) }, dataLen = 1)) }
        assertEquals("unsupported WAV format 1, 8 bits", e.message)
    }
}
