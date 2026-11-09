package io.github.tancheng33.tingo.ui.detail

import io.github.tancheng33.tingo.engine.MinutesVersionInfo
import io.github.tancheng33.tingo.engine.TingoEnginePort
import io.github.tancheng33.tingo.testing.FakeEnginePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 纪要历史：编辑、恢复、删除、导出 SRT。
 *
 * 这里盯的是"界面把动作传给了引擎、并把结果摆回状态里"——引擎自己会不会
 * 正确归档，由 Rust 的 `saving_minutes_archives_the_previous_version` 把关。
 */
class MinutesHistoryTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun engine(versions: List<MinutesVersionInfo> = emptyList()) = FakeVersionEngine(versions)

    private class FakeVersionEngine(
        private val versions: List<MinutesVersionInfo>,
    ) : TingoEnginePort by FakeEnginePort() {
        val restored = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val saved = mutableListOf<Pair<String, String>>()
        val exported = mutableListOf<Pair<String, String>>()
        var versionText = "# 旧版\n"

        override fun minutesHistory(id: String): List<MinutesVersionInfo> = versions

        override fun minutesVersion(id: String, versionId: String): String = versionText

        override fun restoreMinutesVersion(id: String, versionId: String) {
            restored += versionId
        }

        override fun deleteMinutesVersion(id: String, versionId: String) {
            deleted += versionId
        }

        override fun saveMinutes(id: String, text: String) {
            saved += id to text
        }

        override fun exportSrt(id: String, language: String): String {
            exported += id to language
            return "/data/$id/周会.srt"
        }
    }

    private companion object {
        /** 一份能通过自检的最小纪要。 */
        val MINUTES = listOf(
            "# 周会",
            "",
            "## 一句话总结",
            "先修首屏。",
            "",
            "## 议题与讨论",
            "- 首屏慢",
            "",
            "## 决议",
            "- 先修首屏",
            "",
            "## 待办",
            "- 林舟：本周内修完",
            "",
            "## 待定问题与风险",
            "- 排期紧",
            "",
        ).joinToString("\n")
    }

    private val version = MinutesVersionInfo(id = "1-2", savedAt = 1_700_000_000_000uL, preview = "旧版")

    @Test
    fun openingAVersionLoadsItsFullText() = runTest(dispatcher) {
        val engine = engine(listOf(version))
        val vm = DetailViewModel(engine = engine, meetingId = "m", io = dispatcher)
        advanceUntilIdle()

        vm.openVersion("1-2")
        advanceUntilIdle()

        assertEquals("1-2", vm.state.value.preview?.id)
        assertTrue(vm.state.value.preview?.text?.contains("旧版") == true)
    }

    @Test
    fun restoringClosesThePreviewAndArchivesTheCurrentOne() = runTest(dispatcher) {
        val engine = engine(listOf(version))
        val vm = DetailViewModel(engine = engine, meetingId = "m", io = dispatcher)
        advanceUntilIdle()
        vm.openVersion("1-2")
        advanceUntilIdle()

        vm.restoreVersion("1-2")
        advanceUntilIdle()

        assertEquals(listOf("1-2"), engine.restored)
        // 恢复后不该还停在"预览旧版"：当前纪要已经是那一版了
        assertNull(vm.state.value.preview)
    }

    @Test
    fun deletingAVersionAsksTheEngine() = runTest(dispatcher) {
        val engine = engine(listOf(version))
        val vm = DetailViewModel(engine = engine, meetingId = "m", io = dispatcher)
        advanceUntilIdle()

        vm.deleteVersion("1-2")
        advanceUntilIdle()

        assertEquals(listOf("1-2"), engine.deleted)
    }

    @Test
    fun savingEditedMinutesGoesThroughTheEngine() = runTest(dispatcher) {
        val engine = engine()
        val vm = DetailViewModel(engine = engine, meetingId = "m", io = dispatcher)
        advanceUntilIdle()

        vm.saveMinutes("# 周会\n\n改过的一版")
        advanceUntilIdle()

        assertEquals("m", engine.saved.single().first)
        assertTrue(engine.saved.single().second.contains("改过的一版"))
    }

    @Test
    fun regeneratingMinutesSavesThroughTheEngineSoHistorySurvives() = runTest(dispatcher) {
        val engine = engine()
        // 生成器只回一段 markdown；保存动作应当交给引擎（此前是直接写文件，旧版就丢了）
        val service = io.github.tancheng33.tingo.minutes.MinutesService(
            generator = object : io.github.tancheng33.tingo.minutes.MinutesGenerator {
                override suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): String {
                    onToken("# 周会")
                    // 必须凑齐纪要模板要求的章节，否则会走"校验不过 → 重试 → 抛异常"那条路
                    return MINUTES
                }
            },
        )
        val dir = kotlin.io.path.createTempDirectory("minutes").toFile()
        val vm = MinutesViewModel(
            serviceFactory = { service },
            isConfigured = { true },
            meetingDir = dir,
            saveMinutes = { text -> engine.saveMinutes("m", text) },
            io = dispatcher,
        )
        // 有转写才允许生成
        io.github.tancheng33.tingo.transcription.TranscriptWriter.write(
            dir,
            io.github.tancheng33.tingo.transcription.Transcript(
                source = "录音.wav",
                durationMs = 3000L,
                sentences = listOf(
                    io.github.tancheng33.tingo.transcription.TranscriptSentence(0, 0L, 3000L, "先修首屏"),
                ),
            ),
        )
        advanceUntilIdle()

        vm.start()
        advanceUntilIdle()

        assertTrue("生成后应经引擎保存：${engine.saved}", engine.saved.any { it.first == "m" })
    }

    @Test
    fun exportSrtReturnsTheEnginePath() = runTest(dispatcher) {
        val engine = engine()
        val vm = DetailViewModel(engine = engine, meetingId = "m", io = dispatcher)
        advanceUntilIdle()

        val path = vm.exportSrt("zh")

        assertEquals("/data/m/周会.srt", path)
        assertEquals(listOf("m" to "zh"), engine.exported)
    }
}
