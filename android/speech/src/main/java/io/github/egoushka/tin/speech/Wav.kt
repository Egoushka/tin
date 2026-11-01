package io.github.egoushka.tin.speech

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads a 16 kHz mono WAV, 16-bit PCM or 32-bit float (FLEURS ships float). No resampling: other rates are refused. */
object Wav {
    fun read(bytes: ByteArray): FloatArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && tag(b, 0) == "RIFF" && tag(b, 8) == "WAVE") { "not a WAV file" }
        var pos = 12
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = tag(b, pos)
            val len = b.getInt(pos + 4)
            val body = pos + 8
            require(len >= 0 && body + len <= bytes.size || id == "data") { "WAV chunk $id runs past the end" }
            when (id) {
                "fmt " -> {
                    format = b.getShort(body).toInt() and 0xffff
                    channels = b.getShort(body + 2).toInt()
                    rate = b.getInt(body + 4)
                    bits = b.getShort(body + 14).toInt()
                    // WAVE_FORMAT_EXTENSIBLE carries the real format in its sub-format GUID.
                    if (format == 0xfffe && len >= 26) format = b.getShort(body + 24).toInt() and 0xffff
                }
                "data" -> {
                    require(channels == 1 && rate == SAMPLE_RATE) { "need mono $SAMPLE_RATE Hz, got $channels ch $rate Hz" }
                    val n = minOf(len, bytes.size - body)
                    return when {
                        format == 1 && bits == 16 -> FloatArray(n / 2) { b.getShort(body + it * 2) / 32768f }
                        format == 3 && bits == 32 -> FloatArray(n / 4) { b.getFloat(body + it * 4) }
                        else -> throw IllegalArgumentException("unsupported WAV format $format, $bits bits")
                    }
                }
            }
            pos = body + len + (len and 1)
        }
        throw IllegalArgumentException("WAV has no data chunk")
    }

    private fun tag(b: ByteBuffer, at: Int) = String(ByteArray(4) { b.get(at + it) }, Charsets.US_ASCII)
}
