package io.github.tancheng33.tingo.ui.library

import io.github.tancheng33.tingo.data.BatchAction
import io.github.tancheng33.tingo.data.MeetingRepository
import io.github.tancheng33.tingo.engine.BatchOutcome
import io.github.tancheng33.tingo.engine.SearchHit
import io.github.tancheng33.tingo.engine.SearchResults
import io.github.tancheng33.tingo.engine.TrashItem
import io.github.tancheng33.tingo.testing.FakeEnginePort
import io.github.tancheng33.tingo.testing.summary
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

/**
 * 会议管理：多选、批量、搜索、回收站。
 *
 * 这些用例盯的是"界面要求 vs 引擎能力"的接缝：动作名要真的传到引擎、
 * 部分失败要能说出来、删除了要能恢复。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryManageTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(engine: FakeEnginePort) =
        LibraryViewModel(MeetingRepository(engine, dispatcher), dispatcher)

    private fun content(vm: LibraryViewModel): LibraryUiState.Content =
        vm.state.value as LibraryUiState.Content

    @Test
    fun selectionTogglesAndClears() = runTest(dispatcher) {
        val engine = FakeEnginePort(onScan = { listOf(summary("a"), summary("b")) })
        val vm = viewModel(engine)
        advanceUntilIdle()

        vm.toggleSelection("a")
        vm.toggleSelection("b")
        assertEquals(setOf("a", "b"), content(vm).selection)

        vm.toggleSelection("a")
        assertEquals(setOf("b"), content(vm).selection)

        vm.clearSelection()
        assertTrue(content(vm).selection.isEmpty())
    }

    @Test
    fun batchArchiveSendsAllSelectedIds() = runTest(dispatcher) {
        val engine = FakeEnginePort(onScan = { listOf(summary("a"), summary("b")) })
        val vm = viewModel(engine)
        advanceUntilIdle()

        vm.toggleSelection("a")
        vm.toggleSelection("b")
        vm.runBatch(BatchAction.ARCHIVE)
        advanceUntilIdle()

        assertEquals(listOf(Triple(listOf("a", "b"), "archive", "")), engine.batched)
        assertTrue("批量完成后应清空选择", content(vm).selection.isEmpty())
    }

    @Test
    fun partialBatchFailureIsReported() = runTest(dispatcher) {
        val engine = FakeEnginePort(
            onScan = { listOf(summary("a"), summary("b")) },
            onBatch = { ids, _, _ ->
                ids.map { BatchOutcome(id = it, ok = it == "a", error = if (it == "a") "" else "boom") }
            },
        )
        val vm = viewModel(engine)
        advanceUntilIdle()

        vm.toggleSelection("a")
        vm.toggleSelection("b")
        vm.runBatch(BatchAction.ARCHIVE)
        advanceUntilIdle()

        assertEquals(1, vm.lastBatchFailures.value)
    }

    @Test
    fun searchGoesThroughEngineNotLocalSubstring() = runTest(dispatcher) {
        val engine = FakeEnginePort(
            onScan = { listOf(summary("a", title = "周会")) },
            onSearch = { q ->
                SearchResults(
                    query = q,
                    expanded = listOf(q, "验正码"),
                    hits = listOf(SearchHit("a", "周会", "2026-11-01", false, "验正码延迟", 1000uL, "说话人1")),
                )
            },
        )
        val vm = viewModel(engine)
        advanceUntilIdle()

        vm.onQueryChange("验证码")
        advanceUntilIdle()

        assertEquals(listOf("验证码"), engine.searches)
        val hits = content(vm).hits
        assertEquals(1, hits.size)
        assertEquals(1000uL, hits.single().startMs)
    }

    @Test
    fun deleteGoesToTrashAndCanBeRestored() = runTest(dispatcher) {
        val engine = FakeEnginePort(onScan = { listOf(summary("a")) })
        engine.trash = listOf(TrashItem(id = "a", bytes = 42uL))
        val vm = viewModel(engine)
        advanceUntilIdle()

        vm.deleteToTrash("a")
        advanceUntilIdle()
        assertEquals(listOf("a"), engine.trashed)

        vm.restoreFromTrash("a")
        advanceUntilIdle()
        assertEquals(listOf("a"), engine.restored)
    }

    @Test
    fun trashViewListsDeletedMeetings() = runTest(dispatcher) {
        val engine = FakeEnginePort(onScan = { emptyList() })
        engine.trash = listOf(TrashItem(id = "a", bytes = 42uL), TrashItem(id = "b", bytes = 7uL))
        val vm = viewModel(engine)

        vm.showTrash()
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), vm.trash.value.map { it.id })
    }
}
