package io.github.tancheng33.tingo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tancheng33.tingo.minutes.LlamaBridge
import io.github.tancheng33.tingo.minutes.MinutesPrompt
import io.github.tancheng33.tingo.testing.ModelDownloads
import io.github.tancheng33.tingo.transcription.TranscriptSentence
import io.github.tancheng33.tingo.transcription.Transcript
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 长会议不会崩：一小时会议的转写稿（约 1.5 万字）必须能裁进上下文并真的生成出东西。
 *
 * 这条用例的由来是一对真实的坑：预填充超 n_batch 会让进程 SIGABRT（已修），
 * 提示词超上下文则让生成静默返回空串。两者都只在"会议够长"时出现，
 * 所以用一份接近真实时长的转写稿来压。
 */
@RunWith(AndroidJUnit4::class)
class LongTranscriptInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun longTranscript(): Transcript {
        val sentences = (0 until 600).map { index ->
            TranscriptSentence(
                speaker = index % 3,
                startMs = index * 6_000L,
                endMs = index * 6_000L + 5_000L,
                text = "第${index + 1}段讨论：注册页改版让转化率下降，短信验证码延迟另有影响，" +
                    "市场同学认为新渠道流量质量也有问题，下周三之前各自给出结论。",
            )
        }
        return Transcript(source = "录音.wav", durationMs = 600 * 6_000L, sentences = sentences)
    }

    @Test
    fun trimsOneHourTranscriptToContextAndStillGenerates() {
        val catalog = ModelDownloads.catalog(context, "llm.json")
        val file = catalog.files.single()
        val store = ModelDownloads.store(context, "models-llm")
        ModelDownloads.ensure(store, listOf(file))

        val cpu = Runtime.getRuntime().availableProcessors()
        val handle = LlamaBridge.load(store.fileFor(file).absolutePath, nThreads = 4, nCtx = 4_096)
        assertTrue("模型加载失败", handle != 0L)
        try {
            val prompt = MinutesPrompt.build(longTranscript())
            val rawTokens = LlamaBridge.countTokens(handle, prompt)
            android.util.Log.i("TingoMinutes", "一小时转写提示词原始 token 数 = $rawTokens（cpu=$cpu）")
            assertTrue("这份转写稿本应超出上下文，实际 $rawTokens", rawTokens > 4_096 - 256)

            val maxTokens = 512
            val budget = 4_096 - maxTokens - 256
            val result = MinutesPrompt.trimToTokenBudget(prompt, budget) { LlamaBridge.countTokens(handle, it) }
            val fitted = result.prompt ?: error("装不下：预算 $budget")
            val fittedTokens = LlamaBridge.countTokens(handle, fitted)
            assertTrue("必须报告裁剪（否则用户不知道纪要只覆盖一段）", result.trimmed)
            android.util.Log.i("TingoMinutes", "裁剪后 token 数 = $fittedTokens")
            assertTrue("裁剪后必须装进上下文，实际 $fittedTokens", fittedTokens <= budget)
            assertTrue("裁剪要明确告知模型内容被省略", fitted.contains("已省略"))

            val text = LlamaBridge.generate(handle, fitted, maxTokens)
            android.util.Log.i("TingoMinutes", "长会议生成结果长度 = ${text.length} 字符")
            assertTrue("长会议也必须生成出内容，实际长度 ${text.length}", text.isNotBlank())
        } finally {
            LlamaBridge.free(handle)
        }
    }
}
