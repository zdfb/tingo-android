package io.github.tancheng33.tingo

import androidx.test.platform.app.InstrumentationRegistry
import io.github.tancheng33.tingo.minutes.LlamaBridge
import io.github.tancheng33.tingo.testing.ModelDownloads
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 量化版 Qwen3-0.6B 在真机上的生成吞吐。
 *
 * 量的是"预填充"与"逐 token 解码"两段：用户体感最直接的是解码速度，
 * 而首字延迟由预填充决定，混在一起报一个 tok/s 等于什么都没说。
 *
 * 断言只卡一个很松的下限（>1 tok/s）：设备有大小核差异，卡太紧会在慢机上假失败；
 * 真实数值打印在 logcat 与 instrumentation 输出里，用来横向对比机型与线程数。
 */
@RunWith(AndroidJUnit4::class)
class LlamaThroughputInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** 用一份接近真实的转写稿当 prompt（约 700 汉字），而不是几十个字的玩具输入。 */
    private fun realisticPrompt(): String = buildString {
        appendLine(TingoBench.PROMPT_HEADER)
        repeat(12) { index ->
            appendLine(
                "说话人1：第${index + 1}项议题，我们确认了注册页改版、短信验证码延迟和新渠道流量质量" +
                    "这三个原因，分别由产品、后端和市场同学负责跟进，时间节点是下周三之前给出结论。",
            )
        }
    }

    private fun modelFile(): File {
        val catalog = ModelDownloads.catalog(context, "llm.json")
        val file = catalog.files.single()
        val store = ModelDownloads.store(context, "models-llm")
        ModelDownloads.ensure(store, listOf(file))
        return store.fileFor(file)
    }

    @Test
    fun reportsTokensPerSecondForBundledQuantizedModel() {
        val model = modelFile()
        assertTrue("模型应就绪：${model.absolutePath}", model.isFile && model.length() > 0)

        val results = mutableListOf<Triple<Int, Double, Double>>()
        // 线程数扫一遍：手机上是大小核混合，8 线程往往比 4 线程慢（调度开销 + 大核抢不到）
        for (threads in listOf(4, 8)) {
            val handle = LlamaBridge.load(model.absolutePath, nThreads = threads, nCtx = 2048)
            assertTrue("模型加载失败（threads=$threads）", handle != 0L)
            try {
                // 预热一次：首次调用包含 mmap 缺页与缓存填充，不能算进稳态吞吐
                LlamaBridge.bench(handle, "你好", maxTokens = 8)
                // 取三轮中位数：手机上单次测量受调频与后台任务影响，波动能到 ±40%
                val samples = (1..3).mapNotNull { LlamaBridge.bench(handle, realisticPrompt(), maxTokens = 128) }
                assertTrue("基准没有返回数据（threads=$threads）", samples.size == 3)
                val decode = samples.map { it.decodeTokensPerSecond }.sorted()[1]
                val prefill = samples.map { it.prefillTokensPerSecond }.sorted()[1]
                val promptTokens = samples.first().promptTokens
                android.util.Log.i(
                    "TingoThroughput",
                    "threads=$threads prompt=$promptTokens tok " +
                        "prefill_median=${"%.1f".format(prefill)} tok/s " +
                        "decode_median=${"%.2f".format(decode)} tok/s " +
                        "（三轮解码：${samples.joinToString { "%.2f".format(it.decodeTokensPerSecond) }}）",
                )
                results += Triple(threads, decode, prefill)
            } finally {
                LlamaBridge.free(handle)
            }
        }

        assertTrue("至少要有一组结果", results.isNotEmpty())
        assertTrue(
            "生成速度应当大于 1 tok/s，实际 ${results.joinToString { "${it.first}线程=${"%.2f".format(it.second)}" }}",
            results.all { it.second > 1.0 },
        )
    }
}

/** 基准用的固定提示词，避免每次跑都不一样导致数值不可比。 */
private object TingoBench {
    const val PROMPT_HEADER = "你是会议纪要助手。下面是会议转写稿，请按要求写纪要："
}
