package io.github.tancheng33.tingo.ui.record

import io.github.tancheng33.tingo.recording.AudioCapture
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private var now = 0L

    private class FakeCapture(private val durationMs: Long = 1_000) : AudioCapture {
        var started: File? = null
        var pauses = 0
        var resumes = 0
        var stopped = 0
        var failWith: Exception? = null

        override fun start(file: File) {
            failWith?.let { throw it }
            started = file
        }

        override fun pause() { pauses++ }

        override fun resume() { resumes++ }

        override fun stop(): Long {
            stopped++
            return durationMs
        }

        override fun level(): Float = 0.5f
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun root(): File = Files.createTempDirectory("tingo-record").toFile()

    private fun viewModel(capture: FakeCapture) =
        RecordViewModel(capture, root(), clock = { now }, io = dispatcher)

    @Test
    fun startCreatesMeetingDirectoryAndRecordingFile() = runTest(dispatcher) {
        val capture = FakeCapture()
        val vm = viewModel(capture)
        vm.start()

        val state = vm.state.value as RecordUiState.Recording
        assertEquals(0L, state.elapsedMs)
        val file = requireNonNull(capture.started)
        assertTrue(file.parentFile!!.name.endsWith("_录音"))
        assertEquals("录音.wav", file.name)
    }

    @Test
    fun tickerAdvancesElapsedTime() = runTest(dispatcher) {
        val vm = viewModel(FakeCapture())
        vm.start()
        now = 1_200
        advanceTimeBy(500)
        val state = vm.state.value as RecordUiState.Recording
        assertEquals(1_200L, state.elapsedMs)
    }

    @Test
    fun pauseStopsCountingAndResumeContinues() = runTest(dispatcher) {
        val capture = FakeCapture()
        val vm = viewModel(capture)
        vm.start()
        now = 2_000
        vm.pause()

        assertEquals(RecordUiState.Paused(2_000), vm.state.value)
        assertEquals(1, capture.pauses)

        now = 12_000
        vm.resume()
        assertEquals(1, capture.resumes)
        assertEquals(2_000L, (vm.state.value as RecordUiState.Recording).elapsedMs)

        now = 12_500
        advanceTimeBy(400)
        assertEquals(2_500L, (vm.state.value as RecordUiState.Recording).elapsedMs)
    }

    @Test
    fun stopReportsDurationFromCapture() = runTest(dispatcher) {
        val capture = FakeCapture(durationMs = 3_400)
        val vm = viewModel(capture)
        vm.start()
        now = 3_500
        vm.stop()

        val state = vm.state.value as RecordUiState.Finished
        assertEquals(3_400L, state.durationMs)
        assertTrue(state.meetingId.endsWith("_录音"))
        assertEquals(1, capture.stopped)
    }

    @Test
    fun captureFailureSurfacesAsFailed() = runTest(dispatcher) {
        val capture = FakeCapture().apply { failWith = IllegalStateException("麦克风被占用") }
        val vm = viewModel(capture)
        vm.start()

        assertEquals(RecordUiState.Failed("麦克风被占用"), vm.state.value)
    }

    @Test
    fun discardRemovesEmptyMeetingDirectory() = runTest(dispatcher) {
        val root = root()
        val vm = RecordViewModel(FakeCapture(), root, clock = { now }, io = dispatcher)
        vm.start()
        val created = root.listFiles()!!.single()

        vm.discard()

        assertEquals(RecordUiState.Idle, vm.state.value)
        assertTrue(!created.exists())
    }

    private fun <T> requireNonNull(value: T?): T = requireNotNull(value)
}
