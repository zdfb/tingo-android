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

    private val version = MinutesVersionInfo(id = "1-2", savedAt = 1_700_000_000_000uL, preview = "旧版", chars = 4u)

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
    fun deletingAVersionAsksTheEngineAndRefreshesTheList() = runTest(dispatcher) {
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
    fun exportSrtReturnsTheEnginePath() = runTest(dispatcher) {
        val engine = engine()
        val vm = DetailViewModel(engine = engine, meetingId = "m", io = dispatcher)
        advanceUntilIdle()

        val path = vm.exportSrt("zh")

        assertEquals("/data/m/周会.srt", path)
        assertEquals(listOf("m" to "zh"), engine.exported)
    }
}
