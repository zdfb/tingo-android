package io.github.tancheng33.tingo.ui.library

import io.github.tancheng33.tingo.data.BatchAction
import io.github.tancheng33.tingo.data.MeetingRepository
import io.github.tancheng33.tingo.testing.FakeEnginePort
import io.github.tancheng33.tingo.ui.tasks.RunningTask
import io.github.tancheng33.tingo.ui.tasks.TaskCenter
import io.github.tancheng33.tingo.ui.tasks.TaskKind
import kotlinx.coroutines.Dispatchers
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
 * 正在处理的会议不能被删。
 *
 * 桌面在服务端用 `meeting_busy` 挡住这件事；Android 只有一个进程，所以在 ViewModel 里挡。
 * 挡了还要说出来——静默失败会让用户以为按钮坏了。
 */
class LibraryBusyGuardTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(engine: FakeEnginePort, tasks: TaskCenter) = LibraryViewModel(
        repository = MeetingRepository(engine, dispatcher),
        io = dispatcher,
        tasks = tasks,
        language = { "zh" },
    )

    private fun busyTask(meetingId: String) = TaskCenter().apply {
        start(RunningTask.keyOf(TaskKind.TRANSCRIBE, meetingId), meetingId, "周会", TaskKind.TRANSCRIBE)
    }

    @Test
    fun deletingAMeetingThatIsRunningIsRefused() = runTest(dispatcher) {
        val engine = FakeEnginePort()
        val vm = viewModel(engine, busyTask("2026-11-02_1000_周会"))
        advanceUntilIdle()

        vm.deleteToTrash("2026-11-02_1000_周会")
        advanceUntilIdle()

        assertTrue("正在转写的会议不该被删", engine.trashed.isEmpty())
        assertEquals("拒绝要有痕迹，界面才能提示", 1, vm.busyBlocked.value)
    }

    @Test
    fun aFreeMeetingIsStillDeletable() = runTest(dispatcher) {
        val engine = FakeEnginePort()
        val vm = viewModel(engine, busyTask("2026-11-02_1000_别的会"))
        advanceUntilIdle()

        vm.deleteToTrash("2026-11-03_1000_空闲")
        advanceUntilIdle()

        assertEquals(listOf("2026-11-03_1000_空闲"), engine.trashed)
        assertEquals(0, vm.busyBlocked.value)
    }

    @Test
    fun batchDeleteSkipsBusyMeetingsAndCountsThem() = runTest(dispatcher) {
        val engine = FakeEnginePort(onScan = {
            listOf(
                io.github.tancheng33.tingo.engine.MeetingSummary(
                    id = "busy", title = "在跑", date = "2026-11-02", time = "10:00",
                    oneLiner = "", hasMinutes = false, hasTranscript = true, unreadable = false,
                    hasAudio = true, duration = "00:10", speakers = emptyList(), tags = emptyList(),
                    archived = false, sizeBytes = 0uL,
                ),
                io.github.tancheng33.tingo.engine.MeetingSummary(
                    id = "idle", title = "空闲", date = "2026-11-03", time = "10:00",
                    oneLiner = "", hasMinutes = false, hasTranscript = true, unreadable = false,
                    hasAudio = true, duration = "00:10", speakers = emptyList(), tags = emptyList(),
                    archived = false, sizeBytes = 0uL,
                ),
            )
        })
        val vm = viewModel(engine, busyTask("busy"))
        advanceUntilIdle()

        vm.toggleSelection("busy")
        vm.toggleSelection("idle")
        vm.runBatch(BatchAction.DELETE)
        advanceUntilIdle()

        assertEquals("只删不忙的那场", listOf("idle"), engine.batched.flatMap { it.first })
        assertEquals("被跳过的要计数", 1, vm.busyBlocked.value)
        assertEquals("用户看到「1 场没删」，而不是以为全删了", 1, vm.lastBatchFailures.value)
    }
}
