package io.github.tancheng33.tingo.transcription

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
 * 重新转写时沿用用户改过的说话人名。
 *
 * 编号是按"首次发言顺序"给的，只要新结果里这个编号还在，就沿用旧名字；
 * 编号超出新结果的丢弃，避免把不存在的说话人写进 transcript.json。
 */
internal fun carryOverSpeakerNames(
    previous: Map<Int, String>?,
    speakers: List<Int>,
): Map<Int, String> {
    if (previous.isNullOrEmpty() || speakers.isEmpty()) return emptyMap()
    val limit = speakers.max() + 1
    return previous.filterKeys { it in 0 until limit }
}

/** 转写器接口：界面只依赖它，测试里换成假实现。 */
interface Transcriber {
    suspend fun transcribe(wav: File, outputDir: File, onProgress: (Float) -> Unit = {}): Transcript
}

/**
 * 端侧转写流水线：VAD 切段 → 识别 → 标点 → 声纹 → 聚类 → 写 transcript.json。
 *
 * 每一步都只依赖 sherpa-onnx 的 Kotlin API，推理全在本机；调用方负责把模型下载好
 * （[ModelStore]），这里只消费文件。
 */
class TranscriptionPipeline(
    private val store: ModelStore,
    private val catalog: ModelCatalog,
    private val clustering: SpeakerClustering = SpeakerClustering(),
) : Transcriber {
    /**
     * 转写一个 wav 文件并写入 [outputDir] 的 `transcript.json`。
     *
     * @param onProgress 0..1 的进度，用来在界面上显示
     */
    override suspend fun transcribe(
        wav: File,
        outputDir: File,
        onProgress: (Float) -> Unit,
    ): Transcript = requireModel().use { model ->
        val format = WavReader.format(wav)
        require(format.dataBytes > 0) { "录音是空的" }
        val totalSamples = format.dataBytes / 2
        // 在 suspend 上下文里取出协程上下文，供切段回调里检查取消
        val callerContext = coroutineContext

        // 边切边识别：内存里只有当前段的音频，记下来的是文本与声纹向量
        val recognized = ArrayList<RecognizedSegment>()
        cutByVad(model.vad, wav, format.sampleRate) { segment ->
            callerContext.ensureActive()
            val text = model.recognize(segment.samples, format.sampleRate)
            val punctuated = model.punctuate(text)
            val embedding = model.embed(segment.samples, format.sampleRate)
            recognized += RecognizedSegment(segment.startMs, segment.endMs, punctuated, embedding)
            onProgress(if (totalSamples == 0L) 0f else (segment.endSample.toFloat() / totalSamples).coerceIn(0f, 1f))
        }

        if (recognized.isEmpty()) {
            val empty = Transcript(source = wav.name, durationMs = format.durationMs, sentences = emptyList())
            TranscriptWriter.write(outputDir, empty)
            onProgress(1f)
            return@use empty
        }

        val speakers = clustering.cluster(recognized.map { it.embedding })
        // 用户之前改过的说话人名要留住：重新转写不该把「张总」打回「说话人1」
        val renaming = previousNames(outputDir, speakers)
        val transcript = Transcript(
            source = wav.name,
            durationMs = format.durationMs,
            sentences = recognized.flatMapIndexed { index, segment ->
                // 段落里可能有多句话：按句切开，每句带着自己的时间戳（规格要求一句一个 sentence）
                SentenceSplitter.split(segment.text, segment.startMs, segment.endMs)
                    .map { it.copy(speaker = speakers[index]) }
            },
            speakerNames = renaming,
        )
        TranscriptWriter.write(outputDir, transcript)
        onProgress(1f)
        transcript
    }

    private fun previousNames(outputDir: File, speakers: List<Int>): Map<Int, String> =
        carryOverSpeakerNames(TranscriptWriter.read(outputDir)?.speakerNames, speakers)

    /** @param endSample 该段末尾在整段音频里的样本下标，用来算进度 */
    private class Segment(
        val startMs: Long,
        val endMs: Long,
        val endSample: Long,
        val samples: FloatArray,
    )

    /**
     * 流式切段：wav 分块喂给 VAD，每切出一段就交给 [onSegment]，随即丢弃它的音频。
     *
     * 小时级会议录音如果整段读进内存，单是样本就要几百兆；逐段回调意味着
     * 常驻内存只与"最长的一段话"有关，与录音总长无关。
     */
    private fun cutByVad(
        vad: Vad,
        wav: File,
        sampleRate: Int,
        onSegment: (Segment) -> Unit,
    ) {
        val window = vad.config.sileroVadModelConfig.windowSize.takeIf { it > 0 } ?: 512
        var carry = FloatArray(0)

        fun drain() {
            while (!vad.empty()) {
                val speech = vad.front()
                vad.pop()
                val startSample = speech.start
                val endSample = startSample + speech.samples.size
                val startMs = startSample * 1000L / sampleRate
                val endMs = endSample * 1000L / sampleRate
                // 太短的段大概率是噪声或半个字，丢掉
                if (endMs - startMs >= AsrModels.MIN_SPEECH_MS) {
                    onSegment(Segment(startMs, endMs, endSample, speech.samples))
                }
            }
        }

        WavReader.stream(wav) { chunk, _ ->
            val merged = if (carry.isEmpty()) chunk else carry + chunk
            var offset = 0
            while (offset + window <= merged.size) {
                vad.acceptWaveform(merged.copyOfRange(offset, offset + window))
                drain()
                offset += window
            }
            carry = if (offset < merged.size) merged.copyOfRange(offset, merged.size) else FloatArray(0)
        }
        vad.flush()
        drain()
    }

    /** 四个模型都就绪后才建对象；缺任何一个都直接报错，不做半残障运行。 */
    private fun requireModel(): AsrModels = AsrModels.open(store, catalog)
}
