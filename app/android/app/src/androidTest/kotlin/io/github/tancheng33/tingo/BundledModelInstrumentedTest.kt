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
import io.github.tancheng33.tingo.transcription.WavReader
import io.github.tancheng33.tingo.testing.ModelDownloads
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 验证实际下载的模型能加载并识别示例音频。 */
@RunWith(AndroidJUnit4::class)
class BundledModelInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun downloadedModelRecognizesTheSampleClip() {
        val container = AppContainer(context)
        ModelDownloads.ensure(container.models, container.catalog.files)

        val catalog = container.catalog
        val store = container.models
        val paraformer = store.fileFor(catalog.byId("paraformer")!!)
        val tokens = store.fileFor(catalog.byId("tokens")!!)
        val punct = store.fileFor(catalog.byId("punct")!!)
        assertTrue("缺少识别模型：$paraformer", paraformer.isFile)

        val wav = copySample()
        val pcm = WavReader.read(wav)

        val recognizer = OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    paraformer = OfflineParaformerModelConfig(model = paraformer.absolutePath),
                    tokens = tokens.absolutePath,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
            ),
        )
        val punctuation = OfflinePunctuation(
            assetManager = null,
            config = OfflinePunctuationConfig(
                model = OfflinePunctuationModelConfig(ctTransformer = punct.absolutePath, numThreads = 1),
            ),
        )
        try {
            val stream = recognizer.createStream()
            stream.acceptWaveform(pcm.samples, pcm.sampleRate)
            recognizer.decode(stream)
            val raw = recognizer.getResult(stream).text
            stream.release()
            val punctuated = punctuation.addPunctuation(raw)
            android.util.Log.i("TingoBundledModel", "raw=$raw")
            android.util.Log.i("TingoBundledModel", "punctuated=$punctuated")

            assertTrue("识别结果为空：下载的模型可能不可用", raw.isNotBlank())
            assertTrue("识别结果与音频内容不符：$raw", raw.contains("注册") || raw.contains("转化"))
        } finally {
            recognizer.release()
            punctuation.release()
        }
    }

    private fun copySample(): File {
        val target = File(context.cacheDir, "bundled-sample.wav")
        instrumentation.context.assets.open("audio/sample.wav").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }
}
