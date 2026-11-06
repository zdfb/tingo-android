package io.github.tancheng33.tingo.transcription

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import io.github.tancheng33.tingo.models.ModelCatalog
import io.github.tancheng33.tingo.models.ModelStore
import java.io.File
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** 一段识别结果：文本 + 时间 + 声纹。 */
data class RecognizedSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val embedding: FloatArray,
)

/**
 * 端侧转写流水线：VAD 切段 → 识别 → 标点 → 声纹 → 聚类 → 写 transcript.json。
 *
 * 每一步都只依赖 sherpa-onnx 的 Kotlin API，推理全在本机；调用方负责把模型下载好
 * （[ModelStore]），这里只消费文件。
 */
class TranscriptionPipeline(
    private val context: Context,
    private val store: ModelStore,
    private val catalog: ModelCatalog,
    private val clustering: SpeakerClustering = SpeakerClustering(),
) {
    /**
     * 转写一个 wav 文件并写入 [outputDir] 的 `transcript.json`。
     *
     * @param onProgress 0..1 的进度，用来在界面上显示
     */
    suspend fun transcribe(
        wav: File,
        outputDir: File,
        onProgress: (Float) -> Unit = {},
    ): Transcript {
        val model = requireModel()
        val pcm = WavReader.read(wav)
        require(pcm.samples.isNotEmpty()) { "录音是空的" }

        val segments = cutByVad(model.vad, pcm)
        if (segments.isEmpty()) {
            val empty = Transcript(source = wav.name, durationMs = pcm.durationMs, sentences = emptyList())
            TranscriptWriter.write(outputDir, empty)
            onProgress(1f)
            return empty
        }

        val recognized = ArrayList<RecognizedSegment>(segments.size)
        segments.forEachIndexed { index, segment ->
            coroutineContext.ensureActive()
            val text = model.recognize(segment.samples, pcm.sampleRate)
            val punctuated = model.punctuate(text)
            val embedding = model.embed(segment.samples, pcm.sampleRate)
            recognized += RecognizedSegment(segment.startMs, segment.endMs, punctuated, embedding)
            onProgress((index + 1).toFloat() / segments.size)
        }

        val speakers = clustering.cluster(recognized.map { it.embedding })
        val transcript = Transcript(
            source = wav.name,
            durationMs = pcm.durationMs,
            sentences = recognized.mapIndexed { index, segment ->
                TranscriptSentence(
                    speaker = speakers[index],
                    startMs = segment.startMs,
                    endMs = segment.endMs,
                    text = segment.text,
                )
            },
        )
        TranscriptWriter.write(outputDir, transcript)
        return transcript
    }

    private class Segment(val startMs: Long, val endMs: Long, val samples: FloatArray)

    private fun cutByVad(vad: Vad, pcm: Pcm): List<Segment> {
        val window = vad.config.sileroVadModelConfig.windowSize.takeIf { it > 0 } ?: 512
        val result = ArrayList<Segment>()
        var offset = 0
        while (offset + window <= pcm.samples.size) {
            vad.acceptWaveform(pcm.samples.copyOfRange(offset, offset + window))
            while (!vad.empty()) {
                val speech = vad.front()
                vad.pop()
                val startSample = speech.start.toInt()
                val startMs = startSample * 1000L / pcm.sampleRate
                val endMs = (startSample + speech.samples.size) * 1000L / pcm.sampleRate
                result += Segment(startMs, endMs, speech.samples)
            }
            offset += window
        }
        vad.flush()
        while (!vad.empty()) {
            val speech = vad.front()
            vad.pop()
            val startSample = speech.start.toInt()
            result += Segment(
                startSample * 1000L / pcm.sampleRate,
                (startSample + speech.samples.size) * 1000L / pcm.sampleRate,
                speech.samples,
            )
        }
        // 太短的段大概率是噪声或半个字，丢掉
        return result.filter { it.endMs - it.startMs >= MIN_SEGMENT_MS }
    }

    /** 四个模型都就绪后才建对象；缺任何一个都直接报错，不做半残障运行。 */
    private fun requireModel(): Model {
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
            sampleRate = 16_000,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        )

        val recognizer = OfflineRecognizer(
            assetManager = context.assets,
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
            assetManager = context.assets,
            config = OfflinePunctuationConfig(
                model = OfflinePunctuationModelConfig(ctTransformer = path("punct"), numThreads = 1),
            ),
        )

        val extractor = SpeakerEmbeddingExtractor(
            assetManager = context.assets,
            config = SpeakerEmbeddingExtractorConfig(model = path("campplus"), numThreads = 1),
        )

        return Model(Vad(context.assets, vadConfig), recognizer, punctuation, extractor)
    }

    /** 一次性持有四个推理对象，用完释放。 */
    private class Model(
        val vad: Vad,
        private val recognizer: OfflineRecognizer,
        private val punctuation: OfflinePunctuation,
        private val extractor: SpeakerEmbeddingExtractor,
    ) : AutoCloseable {

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

        override fun close() {
            runCatching { vad.release() }
            runCatching { recognizer.release() }
            runCatching { punctuation.release() }
            runCatching { extractor.release() }
        }
    }

    private companion object {
        /** 短于 200 ms 的段基本是噪声 */
        const val MIN_SEGMENT_MS = 200L
    }
}
