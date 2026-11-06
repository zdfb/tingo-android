package io.github.tancheng33.tingo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import io.github.tancheng33.tingo.testing.ModelDownloads
import io.github.tancheng33.tingo.models.ModelCatalog
import io.github.tancheng33.tingo.models.ModelStore
import io.github.tancheng33.tingo.models.UrlFetcher
import io.github.tancheng33.tingo.transcription.WavReader
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真实识别：下载识别与标点模型，跑一段 20 秒的中文会议音频。
 *
 * 夹具来自桌面版 v0.5.2 的真实产出（`录音.m4a` 转成 16 kHz 单声道 wav），
 * 所以这条测试回答的是"手机能不能听出同一段话"，而不是"API 能不能调用"。
 * 音频与模型都不进主 APK：音频随测试 APK，模型由测试自己下载。
 */
@RunWith(AndroidJUnit4::class)
class RecognitionInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun recognizesChineseMeetingAudioOnDevice() {
        val catalog = ModelDownloads.catalog(context)
        val store = ModelDownloads.store(context, "models-asr")

        // 识别 + 词表 + 标点；声纹在别的测试里覆盖，这里只回答"听得出什么"
        ModelDownloads.ensure(store, listOf("paraformer", "tokens", "am-mvn", "punct").map { catalog.byId(it)!! })

        val wav = copySample()
        val pcm = WavReader.read(wav)
        assertTrue(pcm.durationMs in 15_000..25_000)

        val recognizer = OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    paraformer = OfflineParaformerModelConfig(model = store.fileFor(catalog.byId("paraformer")!!).absolutePath),
                    tokens = store.fileFor(catalog.byId("tokens")!!).absolutePath,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
            ),
        )
        val punctuation = OfflinePunctuation(
            assetManager = null,
            config = OfflinePunctuationConfig(
                model = OfflinePunctuationModelConfig(
                    ctTransformer = store.fileFor(catalog.byId("punct")!!).absolutePath,
                    numThreads = 1,
                ),
            ),
        )

        try {
            val stream = recognizer.createStream()
            stream.acceptWaveform(pcm.samples, pcm.sampleRate)
            recognizer.decode(stream)
            val raw = recognizer.getResult(stream).text
            stream.release()

            val punctuated = punctuation.addPunctuation(raw)
            android.util.Log.i("TingoRecognition", "raw=$raw")
            android.util.Log.i("TingoRecognition", "punctuated=$punctuated")

            assertTrue("识别结果为空", raw.isNotBlank())
            // 这段音频讲的是"官网注册转化"，出现这些词说明确实听对了内容
            assertTrue("识别结果与音频内容不符：$raw", raw.contains("注册") || raw.contains("转化"))
            assertTrue("标点没有生效：$punctuated", punctuated != raw || punctuated.contains("。") || punctuated.contains("，"))
        } finally {
            recognizer.release()
            punctuation.release()
        }
    }

    /** 测试 APK 的 assets 里放着 20 秒夹具音频。 */
    private fun copySample(): File {
        val target = File(context.cacheDir, "sample.wav")
        instrumentation.context.assets.open("audio/sample.wav").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }
}
