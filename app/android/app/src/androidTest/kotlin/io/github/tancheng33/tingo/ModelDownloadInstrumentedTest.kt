package io.github.tancheng33.tingo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import io.github.tancheng33.tingo.testing.ModelDownloads
import io.github.tancheng33.tingo.models.ModelCatalog
import io.github.tancheng33.tingo.models.ModelStore
import io.github.tancheng33.tingo.models.UrlFetcher
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 端侧模型下载 + 推理对象初始化的设备级验证。
 *
 * 只下 VAD（629 KB）：它验证的是"清单 → 真实网络 → 校验 → 把模型交给 sherpa"这条链路，
 * 大模型（227 MB）跑同一条路，没必要在测试里反复拖。
 */
@RunWith(AndroidJUnit4::class)
class ModelDownloadInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun downloadsVadThroughRealHttpAndInitialisesSherpa() {
        val catalog = ModelDownloads.catalog(context)
        val vadModel = catalog.byId("vad")!!
        val dir = File(context.cacheDir, "models-test").apply { deleteRecursively(); mkdirs() }
        val store = ModelStore(dir, UrlFetcher())

        assertFalse(store.isReady(vadModel))
        ModelDownloads.ensure(store, listOf(vadModel))

        assertTrue(store.isReady(vadModel))
        assertEquals(vadModel.bytes, store.fileFor(vadModel).length())

        // 把模型真正交给 sherpa：能构造出 Vad 就说明文件与运行时都对得上
        val vad = Vad(
            assetManager = null,
            config = VadModelConfig(
                SileroVadModelConfig(
                    model = store.fileFor(vadModel).absolutePath,
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
            ),
        )
        try {
            // 喂 1 秒静音：不该被当成语音
            repeat(31) { vad.acceptWaveform(FloatArray(512)) }
            vad.flush()
            assertTrue("静音被判成了语音", vad.empty())
        } finally {
            vad.release()
            dir.deleteRecursively()
        }
    }

    @Test
    fun catalogMatchesShippedAsset() {
        val catalog = ModelCatalog.parse(context.assets.open("models.json").bufferedReader().readText())
        assertEquals(6, catalog.files.size)
        assertTrue(catalog.files.all { it.sha256?.length == 64 })
    }
}
