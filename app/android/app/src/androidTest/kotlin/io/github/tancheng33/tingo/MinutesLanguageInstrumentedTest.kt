package io.github.tancheng33.tingo

import androidx.test.platform.app.InstrumentationRegistry
import io.github.tancheng33.tingo.minutes.MinutesLanguage
import io.github.tancheng33.tingo.minutes.MinutesPrompt
import io.github.tancheng33.tingo.minutes.MinutesService
import io.github.tancheng33.tingo.minutes.MinutesGenerator
import io.github.tancheng33.tingo.transcription.Transcript
import io.github.tancheng33.tingo.transcription.TranscriptSentence
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 英文纪要链路在真机上跑一遍：真引擎的术语表 + 真文件写出。
 *
 * 单测里术语表是假的，这里走 `minutesGlossary(lang)`，确保英文纪要拿到的是英文那句
 * （中文句子塞进英文提示词，模型会跟着改口）。
 */
class MinutesLanguageInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun engine(): io.github.tancheng33.tingo.engine.TingoEnginePort {
        val root = File(context.cacheDir, "minutes-lang").apply { deleteRecursively(); mkdirs() }
        return io.github.tancheng33.tingo.engine.TingoEnginePort.native(root.absolutePath)
    }

    private val englishMinutes = """
        # Release sync

        ## Summary
        Ship on Friday.

        ## Discussion
        ### 1. Release
        - Build is green ([00:00])

        ## Decisions
        - Ship Friday ([00:00])

        ## Action Items
        | Item | Owner | Due | Source |
        |---|---|---|---|
        | Cut the release | Lin | Friday | [00:00] |

        ## Open Questions & Risks
        - None
    """.trimIndent()

    @Test
    fun englishMinutesGetAnEnglishGlossarySentence() = runBlocking {
        val engine = engine()
        engine.setHotwords(listOf("Kafka", "网关"))

        val zh = engine.minutesGlossary("zh")
        val en = engine.minutesGlossary("en")

        assertTrue("中文术语表段应该有中文提示：$zh", zh.contains("同音") || zh.contains("专有名词"))
        assertTrue("英文术语表段应该是英文：$en", en.contains("Kafka"))
        assertTrue("英文那句不该是中文：$en", !en.contains("同音"))
    }

    @Test
    fun theServiceWritesEnglishMinutesAndValidatesThemAgainstEnglishSections() = runBlocking {
        val engine = engine()
        val dir = File(context.cacheDir, "minutes-lang-out").apply { deleteRecursively(); mkdirs() }
        val transcript = Transcript(
            source = "录音.wav",
            durationMs = 2_000,
            sentences = listOf(
                TranscriptSentence(0, 0, 1_000, "Let's ship the build on Friday."),
                TranscriptSentence(1, 1_000, 2_000, "The tests are green."),
            ),
        )
        val service = MinutesService(
            generator = object : MinutesGenerator {
                override suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): String {
                    // 提示词必须是英文的，否则模型会输出中文；这里断言后直接给英文章节
                    assertTrue("提示词应该是英文模板", prompt.contains("## Summary"))
                    return englishMinutes
                }
            },
            glossary = { lang -> engine.minutesGlossary(lang) },
            languageSetting = { MinutesLanguage.AUTO },
        )

        val file = service.generate(transcript, dir)

        val text = file.readText()
        assertTrue("英文章节要能通过校验并落盘：${file.name}", text.contains("## Summary"))
        assertEquals(MinutesService.FILE_NAME, file.name)
    }

    @Test
    fun chineseMeetingStillProducesChineseMinutes() = runBlocking {
        val engine = engine()
        val dir = File(context.cacheDir, "minutes-zh-out").apply { deleteRecursively(); mkdirs() }
        val transcript = Transcript(
            source = "录音.wav",
            durationMs = 1_000,
            sentences = listOf(TranscriptSentence(0, 0, 1_000, "今天复盘注册转化。")),
        )
        val seen = mutableListOf<String>()
        val service = MinutesService(
            generator = object : MinutesGenerator {
                override suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): String {
                    seen += prompt
                    return """
                        # 注册转化复盘

                        ## 一句话总结
                        转化率下滑。

                        ## 议题与讨论
                        ### 1. 转化率
                        - 掉了三个点（[00:00]）

                        ## 决议
                        - 先修首屏（[00:00]）

                        ## 待办
                        | 事项 | 负责人 | 截止时间 | 出处 |
                        |---|---|---|---|
                        | 修首屏 | 林舟 | 未明确 | [00:00] |

                        ## 待定问题与风险
                        - 无
                    """.trimIndent()
                }
            },
            glossary = { lang -> engine.minutesGlossary(lang) },
            languageSetting = { MinutesLanguage.AUTO },
        )

        service.generate(transcript, dir)

        assertTrue("中文会议要用中文模板", seen.first().contains("## 一句话总结"))
        assertTrue(MinutesPrompt.REQUIRED_SECTIONS.all { seen.first().contains(it) })
    }
}
