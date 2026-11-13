package io.github.tancheng33.tingo.ui.detail

import io.github.tancheng33.tingo.R
import io.github.tancheng33.tingo.models.HttpFetcher
import io.github.tancheng33.tingo.minutes.MinutesGenerator
import io.github.tancheng33.tingo.minutes.MinutesService
import io.github.tancheng33.tingo.transcription.Transcript
import io.github.tancheng33.tingo.transcription.TranscriptSentence
import io.github.tancheng33.tingo.transcription.TranscriptWriter
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MinutesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val good = """
        # 复盘

        ## 一句话总结
        转化率下滑。

        ## 议题与讨论
        ### 1. 转化
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

    /** 假的 HTTP 层：给 N 字节内容，或直接失败。 */
    private class FakeHttp(private val size: Int, private val fail: Boolean = false) : HttpFetcher {
        override fun open(url: String, rangeStart: Long): HttpFetcher.FetchedStream {
            if (fail) throw java.io.IOException("网络断开")
            val data = ByteArray(size) { 7 }
            val stream = object : java.io.InputStream() {
                private var i = rangeStart.toInt()
                override fun read(): Int = if (i >= data.size) -1 else data[i++].toInt()
            }
            return HttpFetcher.FetchedStream(stream, rangeStart > 0)
        }
    }

    private class FakeGenerator(private val output: String, private val fail: Boolean = false) : MinutesGenerator {
        var calls = 0
        override suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): String {
            calls++
            if (fail) throw IllegalStateException("显存不够")
            return output
        }
    }

    private fun meeting(withTranscript: Boolean = true): File {
        val dir = Files.createTempDirectory("tingo-meeting").toFile()
        if (withTranscript) {
            TranscriptWriter.write(
                dir,
                Transcript("录音.wav", 1_000, listOf(TranscriptSentence(0, 0, 1_000, "复盘注册转化。"))),
            )
        }
        return dir
    }

    private fun viewModel(
        dir: File,
        generator: MinutesGenerator,
        configured: Boolean = true,
    ) = MinutesViewModel(
        serviceFactory = { MinutesService(generator) },
        isConfigured = { configured },
        meetingDir = dir,
        io = dispatcher,
    )

    @Test
    fun highlightsAreIncludedByDefaultAndCanBeExcluded() = runTest(dispatcher) {
        val dir = meeting()
        io.github.tancheng33.tingo.recording.RecordingHighlights.write(dir, listOf(500L))
        val prompts = mutableListOf<String>()
        val generator = object : MinutesGenerator {
            override suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): String {
                prompts += prompt
                return good
            }
        }
        val vm = viewModel(dir, generator)
        vm.start()
        advanceUntilIdle()
        assertTrue(prompts.last().contains("用户标记的重点"))
        assertTrue(prompts.last().contains("复盘注册转化"))

        vm.start(includeHighlights = false)
        advanceUntilIdle()
        assertTrue(!prompts.last().contains("用户标记的重点"))
    }

    @Test
    fun prepareMarksNotConfiguredWhenServiceIsMissing() = runTest(dispatcher) {
        val vm = viewModel(meeting(), FakeGenerator(good), configured = false)

        vm.prepare()
        advanceUntilIdle() // prepare 在 IO 协程里读 transcript.json

        assertEquals(MinutesUiState.NotConfigured, vm.state.value)
    }

    @Test
    fun startRefusesToRunWithoutConfiguration() = runTest(dispatcher) {
        val vm = viewModel(meeting(), FakeGenerator(good), configured = false)

        vm.start()
        advanceUntilIdle()

        // 没配就明确告诉用户去设置，而不是发一个注定失败的请求
        assertEquals(MinutesUiState.NotConfigured, vm.state.value)
    }

    @Test
    fun unreachableServiceGetsItsOwnMessage() = runTest(dispatcher) {
        val vm = viewModel(
            meeting(),
            object : MinutesGenerator {
                override suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): String =
                    throw io.github.tancheng33.tingo.minutes.remote.LlmUnavailable("timeout")
            },
        )

        vm.start()
        advanceUntilIdle()

        val state = vm.state.value as MinutesUiState.Failed
        assertEquals(R.string.err_llm_unreachable, state.reasonRes)
    }

    @Test
    fun prepareStaysIdleWhenEverythingReady() = runTest(dispatcher) {
        val vm = viewModel(meeting(), FakeGenerator(good))

        vm.prepare()
        advanceUntilIdle()

        assertEquals(MinutesUiState.Idle, vm.state.value)
    }

    @Test
    fun prepareDoesNothingWithoutTranscript() = runTest(dispatcher) {
        val vm = viewModel(meeting(withTranscript = false), FakeGenerator(good))

        vm.prepare()
        advanceUntilIdle()

        assertEquals(MinutesUiState.Idle, vm.state.value)
    }

    @Test
    fun startWritesMinutesFile() = runTest(dispatcher) {
        val dir = meeting()
        val vm = viewModel(dir, FakeGenerator(good))

        vm.start()
        advanceUntilIdle()

        val state = vm.state.value as MinutesUiState.Done
        assertTrue(state.file.isFile)
        assertEquals(MinutesService.FILE_NAME, state.file.name)
    }

    @Test
    fun missingTranscriptFailsWithHint() = runTest(dispatcher) {
        val vm = viewModel(meeting(withTranscript = false), FakeGenerator(good))

        vm.start()
        advanceUntilIdle()

        assertEquals(R.string.err_no_transcript, (vm.state.value as MinutesUiState.Failed).reasonRes)
    }

    @Test
    fun rejectedOutputIsReportedWithReason() = runTest(dispatcher) {
        val vm = viewModel(meeting(), FakeGenerator("# 标题\n\n## 一句话总结\n不行"))

        vm.start()
        advanceUntilIdle()

        val state = vm.state.value as MinutesUiState.Failed
        assertEquals(R.string.err_minutes_rejected, state.reasonRes)
        assertTrue("detail 要带上第一个问题：${state.detail}", state.detail!!.contains("## 议题与讨论"))
    }

    @Test
    fun generatorCrashSurfacesMessage() = runTest(dispatcher) {
        val vm = viewModel(meeting(), FakeGenerator(good, fail = true))

        vm.start()
        advanceUntilIdle()

        val state = vm.state.value as MinutesUiState.Failed
        assertEquals(R.string.err_minutes_failed, state.reasonRes)
        assertEquals("显存不够", state.detail)
    }

    @Test
    fun cancelReturnsToIdle() = runTest(dispatcher) {
        val vm = viewModel(meeting(), FakeGenerator(good))

        vm.start()
        vm.cancel()
        advanceUntilIdle()

        assertEquals(MinutesUiState.Idle, vm.state.value)
    }
}
