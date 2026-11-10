package io.github.tancheng33.tingo.ui.settings

import io.github.tancheng33.tingo.engine.AudioCleanup
import io.github.tancheng33.tingo.engine.StorageStats
import io.github.tancheng33.tingo.models.ModelCatalog
import io.github.tancheng33.tingo.models.ModelStore
import io.github.tancheng33.tingo.models.UrlFetcher
import io.github.tancheng33.tingo.settings.AppSettings
import io.github.tancheng33.tingo.settings.KeyValueStore
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 设置页的"清理旧音频"。
 *
 * 这个动作删的是用户的声音文件，错了就是"会议还在、但回听没声音"。所以：
 * 参数必须原样交给引擎（天数、仅归档）、失败后不能让用户以为清了、连点不能删两遍。
 */
class SettingsCleanupAudioTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Store : KeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    private fun viewModel(
        cleanup: suspend (Int, Boolean) -> AudioCleanup = { _, _ ->
            AudioCleanup(freedBytes = 0uL, meetings = 0u, failed = 0u)
        },
    ) = SettingsViewModel(
        store = ModelStore(Files.createTempDirectory("cleanup-models").toFile(), UrlFetcher()),
        catalog = ModelCatalog(emptyList()),
        settings = AppSettings(Store()),
        cleanupAudio = cleanup,
        storageStats = { StorageStats(0u, 0uL, 0uL, 0uL, emptyList()) },
        meetingsRoot = Files.createTempDirectory("cleanup-root").toFile(),
        io = dispatcher,
    )

    @Test
    fun daysAndArchivedFlagReachTheEngine() = runTest(dispatcher) {
        val seen = mutableListOf<Pair<Int, Boolean>>()
        val vm = viewModel { days, archived ->
            seen += days to archived
            AudioCleanup(freedBytes = 2048uL, meetings = 2u, failed = 0u)
        }
        advanceUntilIdle()

        vm.cleanupOldAudio(90, archivedOnly = true)
        advanceUntilIdle()

        assertEquals(listOf(90 to true), seen)
    }

    @Test
    fun resultIsExposedForTheUi() = runTest(dispatcher) {
        val vm = viewModel { _, _ -> AudioCleanup(freedBytes = 4096uL, meetings = 3u, failed = 0u) }
        advanceUntilIdle()

        vm.cleanupOldAudio(30, archivedOnly = false)
        advanceUntilIdle()

        val result = vm.state.value.audioCleanup
        assertEquals(3, result?.meetings?.toInt())
        assertEquals(4096uL, result?.freedBytes)
        assertFalse(vm.state.value.audioCleanupBusy)
    }

    @Test
    fun failureLeavesNoResultAndStopsSpinning() = runTest(dispatcher) {
        val vm = viewModel { _, _ -> error("磁盘被占用") }
        advanceUntilIdle()

        vm.cleanupOldAudio(0, archivedOnly = false)
        advanceUntilIdle()

        assertNull("失败不能留下「已清理」的结果", vm.state.value.audioCleanup)
        assertFalse(vm.state.value.audioCleanupBusy)
    }

    @Test
    fun failureIsFlaggedSoTheUiCanSaySomething() = runTest(dispatcher) {
        val vm = viewModel { _, _ -> error("文件被占用") }
        advanceUntilIdle()

        vm.cleanupOldAudio(30, archivedOnly = false)
        advanceUntilIdle()

        assertTrue("失败必须留下标记：静默失败会让人以为按钮坏了", vm.state.value.audioCleanupFailed)
    }

    @Test
    fun theFlagIsClearedByTheNextAttempt() = runTest(dispatcher) {
        var fail = true
        val vm = viewModel { _, _ ->
            if (fail) error("文件被占用") else AudioCleanup(freedBytes = 8uL, meetings = 1u, failed = 0u)
        }
        advanceUntilIdle()
        vm.cleanupOldAudio(30, archivedOnly = false)
        advanceUntilIdle()
        assertTrue(vm.state.value.audioCleanupFailed)

        fail = false
        vm.cleanupOldAudio(30, archivedOnly = false)
        advanceUntilIdle()

        assertFalse("重试成功后不该还挂着失败提示", vm.state.value.audioCleanupFailed)
        assertEquals(8uL, vm.state.value.audioCleanup?.freedBytes)
    }

    @Test
    fun tappingTwiceRunsOnlyOnce() = runTest(dispatcher) {
        var runs = 0
        val vm = viewModel { _, _ ->
            runs++
            AudioCleanup(freedBytes = 1uL, meetings = 1u, failed = 0u)
        }
        advanceUntilIdle()

        vm.cleanupOldAudio(30, archivedOnly = false)
        vm.cleanupOldAudio(30, archivedOnly = false)
        advanceUntilIdle()

        assertEquals("清理期间再点不该再删一遍", 1, runs)
    }

    @Test
    fun busyIsTrueWhileTheEngineWorks() = runTest(dispatcher) {
        val vm = viewModel { _, _ -> AudioCleanup(freedBytes = 1uL, meetings = 1u, failed = 0u) }
        advanceUntilIdle()

        vm.cleanupOldAudio(30, archivedOnly = false)
        assertTrue("引擎还在删文件时按钮要置灰", vm.state.value.audioCleanupBusy)

        advanceUntilIdle()
        assertFalse(vm.state.value.audioCleanupBusy)
    }

    @Test
    fun theResultRemembersWhichChoiceProducedIt() = runTest(dispatcher) {
        val vm = viewModel { _, _ -> AudioCleanup(freedBytes = 16uL, meetings = 1u, failed = 0u) }
        advanceUntilIdle()

        vm.cleanupOldAudio(90, archivedOnly = true)
        advanceUntilIdle()

        // 界面据此只在"选择仍是 90 天 + 仅归档"时显示这条结果，
        // 否则换成 30 天还挂着"已清理 1 场"会被读成本次结果
        assertEquals(90, vm.state.value.audioCleanupDays)
        assertEquals(true, vm.state.value.audioCleanupArchivedOnly)
    }
}
