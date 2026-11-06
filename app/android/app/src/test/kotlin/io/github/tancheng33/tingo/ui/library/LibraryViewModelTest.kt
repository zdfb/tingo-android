package io.github.tancheng33.tingo.ui.library

import io.github.tancheng33.tingo.data.MeetingRepository
import io.github.tancheng33.tingo.engine.FailureKind
import io.github.tancheng33.tingo.engine.TingoFailure
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

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // ViewModel 用 viewModelScope，它跑在 Dispatchers.Main 上
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(engine: FakeEnginePort) =
        LibraryViewModel(MeetingRepository(engine, dispatcher), dispatcher)

    @Test
    fun showsContentForLoadedMeetings() = runTest(dispatcher) {
        val vm = viewModel(FakeEnginePort(onScan = { listOf(summary("a"), summary("b")) }))
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is LibraryUiState.Content)
        assertEquals(2, (state as LibraryUiState.Content).items.size)
    }

    @Test
    fun emptyLibraryReportsEmpty() = runTest(dispatcher) {
        val vm = viewModel(FakeEnginePort(onScan = { emptyList() }))
        advanceUntilIdle()
        assertEquals(LibraryUiState.Empty, vm.state.value)
    }

    @Test
    fun unreadableEntriesAreCounted() = runTest(dispatcher) {
        val vm = viewModel(
            FakeEnginePort(onScan = { listOf(summary("ok"), summary("broken", unreadable = true)) }),
        )
        advanceUntilIdle()

        val content = vm.state.value as LibraryUiState.Content
        assertEquals(2, content.items.size)
        assertEquals(1, content.unreadableCount)
    }

    @Test
    fun failedScanWithoutPreviousDataReportsFailure() = runTest(dispatcher) {
        val vm = viewModel(FakeEnginePort(onScan = { throw TingoFailure(FailureKind.PERMISSION_DENIED, "nope") }))
        advanceUntilIdle()

        assertEquals(LibraryUiState.Failed(FailureKind.PERMISSION_DENIED), vm.state.value)
    }

    @Test
    fun failedRefreshKeepsWhatUserIsLookingAt() = runTest(dispatcher) {
        var fail = false
        val engine = FakeEnginePort(
            onScan = {
                if (fail) throw TingoFailure(FailureKind.INTERNAL, "boom")
                listOf(summary("a"), summary("b"))
            },
        )
        val vm = viewModel(engine)
        advanceUntilIdle()
        assertEquals(2, (vm.state.value as LibraryUiState.Content).items.size)

        fail = true
        vm.refresh()
        advanceUntilIdle()

        val content = vm.state.value as LibraryUiState.Content
        assertEquals(2, content.items.size)
    }

    @Test
    fun queryFiltersByTitleAndSummary() = runTest(dispatcher) {
        val vm = viewModel(
            FakeEnginePort(
                onScan = {
                    listOf(
                        summary("a", title = "上线协调会", oneLiner = "定了周一发版"),
                        summary("b", title = "季度复盘", oneLiner = "下季度目标"),
                    )
                },
            ),
        )
        advanceUntilIdle()

        vm.onQueryChange("上线")
        var content = vm.state.value as LibraryUiState.Content
        assertEquals(listOf("a"), content.items.map { it.id })
        assertEquals(2, content.totalCount)

        vm.onQueryChange("季度目")
        content = vm.state.value as LibraryUiState.Content
        assertEquals(listOf("b"), content.items.map { it.id })
    }

    @Test
    fun archivedFilterKeepsOnlyArchivedMeetings() = runTest(dispatcher) {
        val vm = viewModel(
            FakeEnginePort(onScan = { listOf(summary("live"), summary("old", archived = true)) }),
        )
        advanceUntilIdle()

        vm.onArchivedOnlyChange(true)
        val content = vm.state.value as LibraryUiState.Content
        assertEquals(listOf("old"), content.items.map { it.id })
        assertTrue(content.archivedOnly)
    }

    @Test
    fun filtersSurviveRefresh() = runTest(dispatcher) {
        // 从详情返回后用户期望筛选还在：refresh 不能把条件重置掉
        val vm = viewModel(FakeEnginePort(onScan = { listOf(summary("live"), summary("old", archived = true)) }))
        advanceUntilIdle()
        vm.onArchivedOnlyChange(true)

        vm.refresh()
        advanceUntilIdle()

        val content = vm.state.value as LibraryUiState.Content
        assertTrue(content.archivedOnly)
        assertEquals(listOf("old"), content.items.map { it.id })
    }

    @Test
    fun archivingRefreshesTheList() = runTest(dispatcher) {
        var archived = false
        val engine = FakeEnginePort(onScan = { listOf(summary("m1", archived = archived)) })
        val vm = LibraryViewModel(MeetingRepository(engine, dispatcher), dispatcher)
        advanceUntilIdle()

        vm.setArchived("m1", true)
        archived = true
        advanceUntilIdle()

        assertEquals(listOf("m1" to true), engine.archived)
        val state = vm.state.value as LibraryUiState.Content
        assertTrue(state.items.single().archived)
    }

    @Test
    fun deletingRemovesTheMeetingFromTheList() = runTest(dispatcher) {
        var meetings = listOf(summary("m1"))
        val engine = FakeEnginePort(onScan = { meetings }, onDelete = { 1_024uL })
        val vm = LibraryViewModel(MeetingRepository(engine, dispatcher), dispatcher)
        advanceUntilIdle()

        vm.delete("m1")
        meetings = emptyList()
        advanceUntilIdle()

        assertEquals(listOf("m1"), engine.deleted)
        assertEquals(LibraryUiState.Empty, vm.state.value)
    }

    @Test
    fun archiveFailureKeepsListAndRecordsReason() = runTest(dispatcher) {
        val engine = FakeEnginePort(
            onScan = { listOf(summary("m1")) },
            onArchive = { _, _ -> throw TingoFailure(FailureKind.PERMISSION_DENIED, "read-only") },
        )
        val vm = LibraryViewModel(MeetingRepository(engine, dispatcher), dispatcher)
        advanceUntilIdle()

        vm.setArchived("m1", true)
        advanceUntilIdle()

        assertEquals(FailureKind.PERMISSION_DENIED, vm.lastError)
        assertTrue(vm.state.value is LibraryUiState.Content)
    }
}
