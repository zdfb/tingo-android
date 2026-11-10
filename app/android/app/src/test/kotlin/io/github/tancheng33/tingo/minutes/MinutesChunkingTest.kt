package io.github.tancheng33.tingo.minutes

import io.github.tancheng33.tingo.transcription.Transcript
import io.github.tancheng33.tingo.transcription.TranscriptSentence
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长会议的分段提炼 → 合并。
 *
 * 整段长转写喂给模型，待办会被大段无关内容稀释（桌面上实测待办从 14 条掉到 6 条）；
 * 这里保证：超过上限就分段、每段都被提炼、最后合并的那一次才带"输出纪要"的要求。
 */
class MinutesChunkingTest {

    private val goodMinutes = """
        # 长会议

        ## 一句话总结
        定了三件事。

        ## 议题与讨论
        ### 1. 排期
        - 国庆前上线（[00:00]）

        ## 决议
        - 排期不变（[00:00]）

        ## 待办
        | 事项 | 负责人 | 截止时间 | 出处 |
        |---|---|---|---|
        | 提测 | 林舟 | 未明确 | [00:00] |

        ## 待定问题与风险
        - 无
    """.trimIndent()

    /** 一场足够长的会：`turns` 句、每句 `chars` 个汉字。 */
    private fun longTranscript(turns: Int, chars: Int = 40): Transcript = Transcript(
        source = "录音.wav",
        durationMs = turns * 1_000L,
        sentences = (0 until turns).map { i ->
            TranscriptSentence(i % 3, i * 1_000L, i * 1_000L + 900, "第${i + 1}项进展".padEnd(chars, '说'))
        },
    )

    @Test
    fun longMeetingsAreSummarizedInPartsThenMerged() = runTest {
        val prompts = mutableListOf<String>()
        val stages = mutableListOf<String>()
        val service = MinutesService(
            generator = object : MinutesGenerator {
                override suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): String {
                    prompts += prompt
                    // 分段提炼给的是条目；最后一次才是完整纪要
                    return if (prompt.contains("要求：")) goodMinutes else "### 第 1 段\n- 要点（[00:00]）"
                }
            },
            languageSetting = { MinutesLanguage.ZH },
            maxChars = 2_000,
        )

        service.generate(
            longTranscript(turns = 200),
            Files.createTempDirectory("2026-10-28_1430_长会").toFile(),
            onStage = { stages += it },
        )

        val chunkPrompts = prompts.filter { it.contains("下面是一场长会议中的一段转写") }
        assertTrue("长会议必须分段提炼：${prompts.size} 次调用", chunkPrompts.size >= 2)
        assertTrue("最后要有合并提示词", prompts.last().contains("分段提炼的笔记"))
        assertTrue("合并时要带格式要求", prompts.last().contains("## 一句话总结"))
        assertEqualProgress("分段提炼 1", stages.first())
        assertTrue("合并阶段要报出来", stages.last().contains("合并成纪要"))
        // 长度上限生效：每段都不该超过 maxChars 太多
        chunkPrompts.forEach { assertTrue("单段不该超限：${it.length}", it.length < 3_000) }
    }

    @Test
    fun shortMeetingsStillGoInOneCall() = runTest {
        val prompts = mutableListOf<String>()
        val service = MinutesService(
            generator = object : MinutesGenerator {
                override suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): String {
                    prompts += prompt
                    return goodMinutes
                }
            },
            languageSetting = { MinutesLanguage.ZH },
        )

        service.generate(Transcript(
            source = "录音.wav",
            durationMs = 1_000,
            sentences = listOf(TranscriptSentence(0, 0, 900, "今天复盘注册转化。")),
        ), Files.createTempDirectory("2026-10-28_1430_短会").toFile())

        assertEquals("短会议不该分段", 1, prompts.size)
        assertFalse(prompts.first().contains("下面是一场长会议中的一段转写"))
    }

    @Test
    fun chunksNeverSplitALine() {
        val lines = List(10) { "第${it}行内容".padEnd(30, '字') }
        val chunks = MinutesPrompt.splitChunks(lines, 100)

        assertTrue(chunks.size > 1)
        assertEquals(lines.size, chunks.sumOf { it.lines().size })
        chunks.forEach { chunk -> chunk.lines().forEach { assertTrue(it.startsWith("第")) } }
    }

    @Test
    fun englishLongMeetingsGetEnglishChunkPrompts() = runTest {
        val prompts = mutableListOf<String>()
        val service = MinutesService(
            generator = object : MinutesGenerator {
                override suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): String {
                    prompts += prompt
                    return if (prompt.contains("Rules:")) {
                        "# Release\n\n## Summary\nShip.\n\n## Discussion\n### 1. Release\n- Green ([00:00])\n\n## Decisions\n- Ship ([00:00])\n\n## Action Items\n| Item | Owner | Due | Source |\n|---|---|---|---|\n| Cut | Lin | Fri | [00:00] |\n\n## Open Questions & Risks\n- None"
                    } else {
                        "### Part 1\n- point ([00:00])"
                    }
                }
            },
            languageSetting = { MinutesLanguage.EN },
            maxChars = 2_000,
        )

        service.generate(
            Transcript(
                source = "录音.wav",
                durationMs = 200_000,
                sentences = (0 until 120).map { i ->
                    TranscriptSentence(i % 2, i * 1_000L, i * 1_000L + 900, "Item $i shipped on the release train today.")
                },
            ),
            Files.createTempDirectory("2026-10-28_1430_Release sync").toFile(),
        )

        assertTrue("英文分段提示词：${prompts.first()}", prompts.any { it.contains("Below is one part (part 1 of") })
        assertTrue("英文合并提示词", prompts.last().contains("Notes by part"))
    }

    private fun assertEqualProgress(expectedPrefix: String, actual: String?) {
        assertTrue("进度文字要以「$expectedPrefix」开头：$actual", actual?.startsWith(expectedPrefix) == true)
    }
}
