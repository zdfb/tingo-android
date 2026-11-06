package io.github.tancheng33.tingo.minutes

import io.github.tancheng33.tingo.transcription.Transcript
import io.github.tancheng33.tingo.transcription.TranscriptSentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MinutesPromptTest {

    private val transcript = Transcript(
        source = "录音.wav",
        durationMs = 65_000,
        sentences = listOf(
            TranscriptSentence(0, 0, 3_000, "今天复盘官网注册转化。"),
            TranscriptSentence(1, 3_424, 9_000, "转化率从百分之十二掉到百分之八点五。"),
            TranscriptSentence(0, 61_000, 65_000, "那先修首屏。"),
        ),
        speakerNames = mapOf(0 to "林舟"),
    )

    @Test
    fun promptCarriesTranscriptWithTimestampsAndNames() {
        val prompt = MinutesPrompt.build(transcript)

        assertTrue(prompt.contains("[00:00] 林舟：今天复盘官网注册转化。"))
        assertTrue(prompt.contains("[00:03] 说话人2：转化率从百分之十二掉到百分之八点五。"))
        assertTrue(prompt.contains("[01:01] 林舟：那先修首屏。"))
    }

    @Test
    fun promptStatesTheRequiredStructure() {
        val prompt = MinutesPrompt.build(transcript)

        MinutesPrompt.REQUIRED_SECTIONS.forEach { assertTrue("提示词里缺章节 $it", prompt.contains(it)) }
        assertTrue(prompt.contains(MinutesPrompt.FISHBONE_SECTION))
        assertTrue(prompt.contains("```fishbone"))
    }

    @Test
    fun longTranscriptIsClippedWithNotice() {
        val long = transcript.copy(
            sentences = (0 until 1_000).map { TranscriptSentence(0, it * 1_000L, it * 1_000L + 900, "这一句有一点长，需要被截断。") },
        )
        val prompt = MinutesPrompt.build(long, maxChars = 2_000)

        assertTrue(prompt.contains("后续内容已省略"))
        assertTrue(prompt.length < 6_000)
    }

    @Test
    fun acceptsWellFormedOutput() {
        val markdown = """
            # 官网注册转化复盘

            ## 一句话总结
            注册转化率下滑，问题出在提交环节。

            ## 议题与讨论
            ### 1. 转化率下滑
            - 从 12% 掉到 8.5%（[00:03]）

            ## 决议
            - 先修首屏（[01:01]）

            ## 待办
            | 事项 | 负责人 | 截止时间 | 出处 |
            |---|---|---|---|
            | 修首屏 | 林舟 | 未明确 | [01:01] |

            ## 待定问题与风险
            - 无

            ## 原因分析
            ```fishbone
            {"problem": "为什么提交环节流失？", "branches": [{"title": "必填项变多", "detail": "新版要求先填邮箱", "refs": ["00:03"]}]}
            ```
        """.trimIndent()

        assertEquals(emptyList<String>(), MinutesPrompt.validate(markdown))
        assertEquals(1, MinutesPrompt.fishboneBodies(markdown).size)
    }

    @Test
    fun reportsMissingSections() {
        val problems = MinutesPrompt.validate("# 标题\n\n## 一句话总结\n没内容")

        assertTrue(problems.any { it.contains("## 决议") })
        assertTrue(problems.any { it.contains("## 待办") })
    }

    @Test
    fun reportsBrokenFishboneJson() {
        // 把鱼骨图那行换成非法 JSON（缩进随 trimIndent 变化，按内容定位更稳）
        val markdown = validMinutes().lines().joinToString("\n") { line ->
            if (line.contains("\"problem\"")) "        {problem: 这不是 JSON}" else line
        }

        val problems = MinutesPrompt.validate(markdown)

        assertTrue(problems.any { it.contains("不是合法 JSON") })
    }

    @Test
    fun reasonsSectionWithoutFenceIsReported() {
        val markdown = validMinutes().replace("```fishbone", "```json")

        assertTrue(MinutesPrompt.validate(markdown).any { it.contains("没有 fishbone 代码块") })
    }

    @Test
    fun retryPromptKeepsOriginalAndListsProblems() {
        val original = MinutesPrompt.build(transcript)
        val retried = MinutesPrompt.retryPrompt(original, listOf("缺少章节：## 待办"))

        assertTrue(retried.startsWith(original))
        assertTrue(retried.contains("缺少章节：## 待办"))
    }

    @Test
    fun timestampsAreMinuteSecond() {
        assertEquals("00:00", MinutesPrompt.mmss(0))
        assertEquals("01:01", MinutesPrompt.mmss(61_400))
        assertEquals("12:34", MinutesPrompt.mmss(754_000))
    }

    private fun validMinutes(): String = """
        # 官网注册转化复盘

        ## 一句话总结
        注册转化率下滑，问题出在提交环节。

        ## 议题与讨论
        ### 1. 转化率下滑
        - 从 12% 掉到 8.5%（[00:03]）

        ## 决议
        - 先修首屏（[01:01]）

        ## 待办
        | 事项 | 负责人 | 截止时间 | 出处 |
        |---|---|---|---|
        | 修首屏 | 林舟 | 未明确 | [01:01] |

        ## 待定问题与风险
        - 无

        ## 原因分析
        ```fishbone
        {"problem": "为什么提交环节流失？", "branches": [{"title": "必填项变多", "detail": "新版要求先填邮箱", "refs": ["00:03"]}]}
        ```
    """.trimIndent()
}
