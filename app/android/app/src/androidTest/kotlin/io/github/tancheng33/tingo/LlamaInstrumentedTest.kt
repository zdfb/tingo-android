package io.github.tancheng33.tingo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tancheng33.tingo.minutes.LlamaBridge
import io.github.tancheng33.tingo.testing.ModelDownloads
import io.github.tancheng33.tingo.models.ModelCatalog
import io.github.tancheng33.tingo.models.ModelStore
import io.github.tancheng33.tingo.models.UrlFetcher
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 端侧语言模型：下载 Qwen3-0.6B（Q4_K_M）→ 加载 → 生成几个 token。
 *
 * 只生成很短的输出：模拟器是纯软件模拟的 arm64，实测吞吐远低于真机，
 * 这里要回答的是"能不能在本机加载并跑起来"，不是"跑得多快"。
 * 吞吐数据记在日志里，真机测量留给实机验证。
 */
@RunWith(AndroidJUnit4::class)
class LlamaInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun loadsQuantisedModelAndGeneratesText() {
        assertTrue("原生库不可用（ABI 不受支持？）", LlamaBridge.isAvailable)

        val catalog = ModelDownloads.catalog(context, "llm.json")
        val file = catalog.files.single()
        val store = ModelDownloads.store(context, "models-llm")
        ModelDownloads.ensure(store, listOf(file))
        val path = store.fileFor(file).absolutePath

        val started = System.currentTimeMillis()
        val handle = LlamaBridge.load(path, nThreads = 4, nCtx = 512)
        val loadMs = System.currentTimeMillis() - started
        assertTrue("模型加载失败", handle != 0L)

        try {
            val genStart = System.currentTimeMillis()
            val text = LlamaBridge.generate(
                handle,
                "用一句话说：我们今天讨论了注册转化率下降。",
                maxTokens = 12,
            )
            val genMs = System.currentTimeMillis() - genStart
            android.util.Log.i(
                "TingoLlama",
                "loadMs=$loadMs genMs=$genMs out=$text",
            )
            assertTrue("没有生成任何文本", text.isNotBlank())
        } finally {
            LlamaBridge.free(handle)
        }
    }
}
