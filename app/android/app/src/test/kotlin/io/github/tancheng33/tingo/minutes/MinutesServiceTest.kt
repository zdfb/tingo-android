package io.github.tancheng33.tingo.minutes

import io.github.tancheng33.tingo.transcription.Transcript
import io.github.tancheng33.tingo.transcription.TranscriptSentence
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinutesServiceTest {

    private val transcript = Transcript(
        source = "录音.wav",
        durationMs = 10_000,
        sentences = listOf(TranscriptSentence(0, 0, 10_000, "今天复盘注册转化。")),
    )

    private val goodMinutes = """
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

    private class FakeGenerator(private val outputs: MutableList<String>) : MinutesGenerator {
        val prompts = mutableListOf<String>()
        override suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): String {
            prompts += prompt
            return outputs.removeFirstOrNull() ?: ""
        }
    }

    private fun dir(): File = Files.createTempDirectory("tingo-minutes").toFile()

    @Test
    fun writesMinutesWhenOutputPassesValidation() = runTest {
        val meeting = dir()
        val generator = FakeGenerator(mutableListOf(goodMinutes))
        val file = MinutesService(generator).generate(transcript, meeting)

        assertEquals(MinutesService.FILE_NAME, file.name)
        assertTrue(file.readText().contains("## 决议"))
        assertFalse(File(meeting, "${MinutesService.FILE_NAME}.tmp").exists())
        assertEquals(1, generator.prompts.size)
    }

    @Test
    fun retriesOnceWithProblemHint() = runTest {
        val generator = FakeGenerator(mutableListOf("# 标题\n\n## 一句话总结\n没别的了", goodMinutes))
        val file = MinutesService(generator).generate(transcript, dir())

        assertEquals(2, generator.prompts.size)
        assertTrue("重试提示里要带问题清单", generator.prompts[1].contains("上一次的输出不符合要求"))
        assertTrue(file.readText().contains("## 待办"))
    }

    @Test
    fun givesUpAfterSecondFailureAndWritesNothing() = runTest {
        val meeting = dir()
        val bad = "# 标题\n\n## 一句话总结\n还是不行"
        val generator = FakeGenerator(mutableListOf(bad, bad))

        val failure = runCatching { MinutesService(generator).generate(transcript, meeting) }.exceptionOrNull()

        assertTrue(failure is MinutesService.MinutesRejected)
        assertTrue((failure as MinutesService.MinutesRejected).problems.any { it.contains("## 决议") })
        assertFalse(File(meeting, MinutesService.FILE_NAME).exists())
    }

    @Test
    fun emptyTranscriptIsRejectedBeforeCallingModel() = runTest {
        val generator = FakeGenerator(mutableListOf(goodMinutes))

        val failure = runCatching {
            MinutesService(generator).generate(transcript.copy(sentences = emptyList()), dir())
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, generator.prompts.size)
    }

    @Test
    fun writeReplacesExistingMinutes() {
        val meeting = dir()
        val service = MinutesService(FakeGenerator(mutableListOf()))
        service.write(meeting, goodMinutes)
        val second = service.write(meeting, "# 新的标题\n\n## 一句话总结\n换了一份")

        assertTrue(second.readText().startsWith("# 新的标题"))
        assertEquals(1, meeting.listFiles()!!.count { it.name == MinutesService.FILE_NAME })
    }
}
