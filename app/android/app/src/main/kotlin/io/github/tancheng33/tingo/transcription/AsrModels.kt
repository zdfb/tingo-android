package io.github.tancheng33.tingo.transcription

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import io.github.tancheng33.tingo.models.ModelCatalog
import io.github.tancheng33.tingo.models.ModelStore

/**
 * 四个语音模型（VAD、识别、标点、声纹）的持有者。
 *
 * 转写管线与"边录边转"共用同一份配置：两处各写一份参数，迟早会漂移成
 * "实时看到的分句和最终转写不一样"这种解释不清的问题。
 */
class AsrModels internal constructor(
    val vad: Vad,
    private val recognizer: OfflineRecognizer,
    private val punctuation: OfflinePunctuation,
    private val extractor: SpeakerEmbeddingExtractor,
) : AutoCloseable, LiveAsr {

    fun recognize(samples: FloatArray, sampleRate: Int): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    fun punctuate(text: String): String =
        if (text.isBlank()) text else punctuation.addPunctuation(text)

    fun embed(samples: FloatArray, sampleRate: Int): FloatArray {
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            if (!extractor.isReady(stream)) return FloatArray(0)
            return extractor.compute(stream)
        } finally {
            stream.release()
        }
    }

    // ---- LiveAsr：边录边转用 ----

    override fun acceptWindow(window: FloatArray) {
        vad.acceptWaveform(window)
    }

    override fun hasSegment(): Boolean = !vad.empty()

    override fun popSegment(): FloatArray {
        val samples = vad.front().samples
        vad.pop()
        return samples
    }

    override fun flush() {
        vad.flush()
    }

    override fun recognize(samples: FloatArray): String = punctuate(recognize(samples, SAMPLE_RATE))

    override fun speakerEmbedding(samples: FloatArray): FloatArray = embed(samples, SAMPLE_RATE)

    override fun close() {
        runCatching { vad.release() }
        runCatching { recognizer.release() }
        runCatching { punctuation.release() }
        runCatching { extractor.release() }
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /**
         * 短于这个人声段就当噪声丢掉（毫秒）。
         *
         * 正式转写与边录边转共用：两处各写一个 200ms 迟早会漂移成
         * "实时看到的句子比最终转写少"这种解释不清的问题。
         */
        const val MIN_SPEECH_MS = 200L

        /** 模型没就绪时抛 [IllegalStateException]，调用方据此提示"去下载模型"。 */
        fun open(store: ModelStore, catalog: ModelCatalog): AsrModels {
            val missing = catalog.files.filterNot { store.isReady(it) }
            check(missing.isEmpty()) { "模型未就绪：${missing.joinToString { it.id }}" }
            fun path(id: String): String = store.fileFor(catalog.byId(id)!!).absolutePath

            val vadConfig = VadModelConfig(
                SileroVadModelConfig(
                    model = path("vad"),
                    threshold = 0.5f,
                    minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = 30f,
                ),
                TenVadModelConfig(),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            )

            val recognizer = OfflineRecognizer(
                assetManager = null,
                config = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        paraformer = OfflineParaformerModelConfig(model = path("paraformer")),
                        tokens = path("tokens"),
                        numThreads = 2,
                        debug = false,
                        provider = "cpu",
                    ),
                ),
            )

            val punctuation = OfflinePunctuation(
                assetManager = null,
                config = OfflinePunctuationConfig(
                    model = OfflinePunctuationModelConfig(ctTransformer = path("punct"), numThreads = 1),
                ),
            )

            val extractor = SpeakerEmbeddingExtractor(
                assetManager = null,
                config = SpeakerEmbeddingExtractorConfig(model = path("campplus"), numThreads = 1),
            )

            // 模型在文件系统上：assetManager 必须传 null，否则 sherpa 会当成 asset 去读并直接 abort
            return AsrModels(Vad(assetManager = null, config = vadConfig), recognizer, punctuation, extractor)
        }
    }
}
