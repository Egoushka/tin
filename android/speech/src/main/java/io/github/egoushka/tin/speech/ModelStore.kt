package io.github.egoushka.tin.speech

import java.io.File
import java.security.MessageDigest

data class ModelFile(val name: String, val size: Long, val sha256: String, val url: String)

data class Model(val name: String, val files: List<ModelFile>)

object Models {
    private const val HF = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main"

    /** NVIDIA Parakeet-tdt-0.6b-v3 (25 European languages, Ukrainian included), int8, as sherpa-onnx packs it. */
    val PARAKEET_V3_INT8 = Model(
        "parakeet-tdt-0.6b-v3-int8",
        listOf(
            ModelFile("encoder.int8.onnx", 652_184_281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247", "$HF/encoder.int8.onnx"),
            ModelFile("decoder.int8.onnx", 11_845_275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e", "$HF/decoder.int8.onnx"),
            ModelFile("joiner.int8.onnx", 6_355_277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3", "$HF/joiner.int8.onnx"),
            ModelFile("tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d", "$HF/tokens.txt"),
        ),
    )

    val SILERO_VAD = Model(
        "silero-vad",
        listOf(
            ModelFile(
                "silero_vad.onnx", 643_854, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            ),
        ),
    )
}

/** Model files live under `<root>/<model name>/`, each pinned by size and SHA-256. */
class ModelStore(private val root: File) {
    fun dir(model: Model) = File(root, model.name)

    fun file(model: Model, name: String) = File(dir(model), name)

    /**
     * What is wrong with [model] on disk, empty when nothing is. The size check is instant; [deep] also hashes every
     * file (seconds for the 650 MB encoder), which is worth doing once after a copy or download.
     */
    fun verify(model: Model, deep: Boolean = false): List<String> = model.files.mapNotNull { m ->
        val f = file(model, m.name)
        when {
            !f.isFile -> "${m.name}: missing"
            f.length() != m.size -> "${m.name}: ${f.length()} bytes, expected ${m.size}"
            deep && sha256(f) != m.sha256 -> "${m.name}: SHA-256 mismatch"
            else -> null
        }
    }

    companion object {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
