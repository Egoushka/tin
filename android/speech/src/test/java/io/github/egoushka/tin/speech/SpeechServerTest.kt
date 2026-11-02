package io.github.egoushka.tin.speech

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpeechServerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val rec = CountingRecognizer()
    private val transcripts = Transcripts()
    private var loaded = true
    private var closed = 0
    private val server by lazy {
        SpeechServer(
            listener = { if (loaded) Listener(FakeVad(), rec, chunkSamples = 100) else null },
            mic = {
                object : AudioSource by ArraySource(audio(0f to 100, 0.5f to 400, 0f to 100)), AutoCloseable {
                    override fun close() { closed++ }
                }
            },
            benchDir = tmp.root,
            transcripts = transcripts,
        )
    }

    private fun call(line: String): JsonObject = Json.parseToJsonElement(server.handle(line)).jsonObject

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

    @Test
    fun `transcribe from the mic`() {
        val r = call("""{"op":"transcribe","seconds":2}""")
        assertEquals("true", r.str("ok"))
        assertEquals("n=400", r.str("text"))
        assertEquals("0.025", r.str("audio_s"))
        assertEquals(1, closed)
        assertEquals(false, server.micBusy.get())
    }

    @Test
    fun `transcribe a bench file`() {
        val b = ByteBuffer.allocate(44 + 4 * 1000).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(b.capacity() - 8).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(3).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 4)
            .putShort(4).putShort(32)
        b.put("data".toByteArray()).putInt(4000)
        File(tmp.root, "clip-1.wav").writeBytes(b.array())
        val r = call("""{"op":"transcribe","file":"clip-1.wav"}""")
        assertEquals("n=1000", r.str("text"))
    }

    @Test
    fun `file names with paths are refused before touching the disk`() {
        for (bad in listOf("../x.wav", "/sdcard/x.wav", ".hidden.wav", "a/b.wav", "x.mp3")) {
            val r = call("""{"op":"transcribe","file":"$bad"}""")
            assertEquals(bad, "false", r.str("ok"))
            assertEquals(bad, "file must be a bare .wav name in the bench folder", r.str("error"))
        }
        assertEquals("no such file: nope.wav", call("""{"op":"transcribe","file":"nope.wav"}""").str("error"))
    }

    @Test
    fun `seconds out of range, unknown op, bad json, no model, busy mic`() {
        assertEquals("seconds must be 1 to 60", call("""{"op":"transcribe","seconds":61}""").str("error"))
        assertEquals("seconds must be 1 to 60", call("""{"op":"transcribe","seconds":0.5}""").str("error"))
        assertEquals("unknown op: dance", call("""{"op":"dance"}""").str("error"))
        assertEquals("false", call("not json").str("ok"))
        server.micBusy.set(true)
        assertEquals("mic busy (push-to-talk or another command)", call("""{"op":"transcribe"}""").str("error"))
        server.micBusy.set(false)
        loaded = false
        assertEquals("speech model not loaded; open tin on the phone", call("""{"op":"transcribe"}""").str("error"))
    }

    @Test
    fun `transcripts since an id`() {
        transcripts.add(1L, Heard("один", 1.0, 5))
        transcripts.add(2L, Heard("two", 2.0, 6))
        val items = call("""{"op":"transcripts","since_id":1}""")["items"]!!.jsonArray
        assertEquals(1, items.size)
        assertEquals("two", items[0].jsonObject.str("text"))
        assertEquals("2", items[0].jsonObject.str("id"))
        assertEquals(2, call("""{"op":"transcripts"}""")["items"]!!.jsonArray.size)
    }
}
