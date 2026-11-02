package io.github.egoushka.tin.speech

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** Parakeet through sherpa-onnx. Loading takes seconds and about 1 GB: build one and keep it. */
class SherpaRecognizer(store: ModelStore, threads: Int = 4) : Recognizer, AutoCloseable {
    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = store.file(Models.PARAKEET_V3_INT8, "encoder.int8.onnx").path,
                    decoder = store.file(Models.PARAKEET_V3_INT8, "decoder.int8.onnx").path,
                    joiner = store.file(Models.PARAKEET_V3_INT8, "joiner.int8.onnx").path,
                ),
                tokens = store.file(Models.PARAKEET_V3_INT8, "tokens.txt").path,
                numThreads = threads,
                modelType = "nemo_transducer",
            ),
            decodingMethod = "greedy_search",
        ),
    )

    override fun decode(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()
}

/** Silero VAD through sherpa-onnx; segments end after 0.5 s of silence and are cut at 30 s. */
class SherpaVad(store: ModelStore) : VoiceDetector, AutoCloseable {
    private val vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = store.file(Models.SILERO_VAD, "silero_vad.onnx").path,
                minSilenceDuration = 0.5f,
                maxSpeechDuration = 30f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    override fun accept(chunk: FloatArray) = vad.acceptWaveform(chunk)

    override fun poll(): FloatArray? {
        if (vad.empty()) return null
        val s = vad.front().samples
        vad.pop()
        return s
    }

    override fun flush() = vad.flush()

    override fun reset() = vad.reset()

    override fun close() = vad.release()
}
