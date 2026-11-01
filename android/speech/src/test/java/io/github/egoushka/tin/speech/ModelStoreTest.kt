package io.github.egoushka.tin.speech

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val hello = "hello\n".toByteArray()
    private val helloSha = "5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03"
    private val model = Model("m", listOf(ModelFile("a.bin", 6, helloSha, "x"), ModelFile("b.bin", 6, helloSha, "x")))

    @Test
    fun `reports missing, wrong size and wrong hash`() {
        val store = ModelStore(tmp.root)
        assertEquals(listOf("a.bin: missing", "b.bin: missing"), store.verify(model))
        store.dir(model).mkdirs()
        File(store.dir(model), "a.bin").writeBytes(hello)
        File(store.dir(model), "b.bin").writeBytes("hi".toByteArray())
        assertEquals(listOf("b.bin: 2 bytes, expected 6"), store.verify(model))
        File(store.dir(model), "b.bin").writeBytes("HELLO\n".toByteArray())
        assertEquals(emptyList<String>(), store.verify(model))
        assertEquals(listOf("b.bin: SHA-256 mismatch"), store.verify(model, deep = true))
    }

    @Test
    fun `pinned manifests are complete`() {
        for (m in listOf(Models.PARAKEET_V3_INT8, Models.SILERO_VAD)) {
            for (f in m.files) {
                assertEquals(64, f.sha256.length)
                assert(f.size > 0 && f.url.startsWith("https://") && f.url.endsWith(f.name)) { f.toString() }
            }
        }
    }
}
