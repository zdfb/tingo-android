package io.github.tancheng33.tingo.ui.record

import io.github.tancheng33.tingo.R
import io.github.tancheng33.tingo.recording.AudioCapture
import io.github.tancheng33.tingo.recording.WavWriter
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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

        private var writer: WavWriter? = null

        override fun start(file: File) {
            failWith?.let { throw it }
            started = file
            writer = WavWriter(file, 16_000)
        }

        /** 追加一段静音，模拟真实写盘，供"合成 录音.wav"的用例使用。 */
        fun emit(pcm: ByteArray) {
            writer?.append(pcm)
        }

        override fun pause() { pauses++ }

        override fun resume() { resumes++ }

        override fun stop(): Long {
            stopped++
            writer?.close()
            writer = null
            return durationMs
        }

        override fun level(): Float = 0.5f
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun root(): File = Files.createTempDirectory("tingo-record").toFile()

    private fun TestScope.viewModel(capture: FakeCapture, root: File = root()) =
        RecordViewModel(capture, root, clock = { now }, io = dispatcher, scope = { backgroundScope })

    @Test
    fun startCreatesMeetingDirectoryAndRecordingFile() = runTest(dispatcher) {
        val capture = FakeCapture()
        val vm = viewModel(capture)
        vm.start()

        val state = vm.state.value as RecordUiState.Recording
        assertEquals(0L, state.elapsedMs)
        val file = requireNonNull(capture.started)
        assertTrue(file.parentFile!!.name.endsWith("_录音"))
        // 录制过程中先写原始音轨，结束时合成 录音.wav
        assertEquals("mic.wav", file.name)
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

        val failed = vm.state.value as RecordUiState.Failed
        assertEquals(R.string.err_mic_unavailable, failed.reasonRes)
        assertEquals("麦克风被占用", failed.detail)
    }

    @Test
    fun discardRemovesEmptyMeetingDirectory() = runTest(dispatcher) {
        val root = root()
        val vm = RecordViewModel(FakeCapture(), root, clock = { now }, io = dispatcher, scope = { backgroundScope })
        vm.start()
        val created = root.listFiles()!!.single()

        vm.discard()

        assertEquals(RecordUiState.Idle, vm.state.value)
        assertTrue(!created.exists())
    }

    private fun <T> requireNonNull(value: T?): T = requireNotNull(value)

    @Test
    fun stoppingMergesRawTracksIntoRecordingFile() = runTest(dispatcher) {
        val root = root()
        val capture = FakeCapture()
        val vm = viewModel(capture, root)
        vm.start()
        capture.emit(ByteArray(3_200))
        vm.stop()
        // 混音在 IO 协程里做：测试调度器上要显式跑到空闲
        advanceUntilIdle()

        val dir = root.listFiles()!!.single()
        val mixed = File(dir, RecordViewModel.RECORDING_FILE)

        assertTrue("停止后应合成 录音.wav", mixed.isFile)
        assertTrue("合成结果不能是空文件", mixed.length() > 44)
        assertTrue("原始音轨保留在磁盘上，便于排查", File(dir, RecordViewModel.MIC_TRACK).isFile)
    }

    @Test
    fun meetingIdMatchesEngineFolderPattern() = runTest(dispatcher) {
        val root = root()
        val vm = viewModel(FakeCapture(), root)
        vm.start()

        val id = root.listFiles()!!.single().name
        // 引擎 meetings.rs 的 FOLDER 正则：yyyy-MM-dd_HHmm_标题
        assertTrue("目录名不符合引擎约定：$id", Regex("""^(\d{4}-\d{2}-\d{2})_(\d{2})(\d{2})_(.+)$""").matches(id))
    }

    @Test
    fun secondRecordingInTheSameMinuteDoesNotReuseFolder() = runTest(dispatcher) {
        val root = root()
        viewModel(FakeCapture(), root).start()
        val second = viewModel(FakeCapture(), root)
        second.start()

        val names = root.listFiles()!!.map { it.name }.sorted()
        assertEquals(2, names.size)
        assertTrue("第二个会议应带序号后缀：$names", names.any { it.endsWith("_2") })
    }

    @Test
    fun interruptionMarksRecordingAsFailedAndKeepsFile() = runTest(dispatcher) {
        val root = root()
        val capture = FakeCapture()
        val vm = viewModel(capture, root)

        vm.start()
        advanceTimeBy(1_000)
        vm.onInterrupted()

        val failed = vm.state.value as RecordUiState.Failed
        assertEquals(R.string.err_recording_interrupted, failed.reasonRes)
        // 会议目录仍在（采集端已写入的部分留在磁盘上，用户还能补转写）
        val meeting = root.listFiles()!!.single()
        assertTrue(meeting.isDirectory)
        assertEquals(1, capture.stopped)
    }

    @Test
    fun interruptionIsIgnoredWhenNothingIsRecording() = runTest(dispatcher) {
        val vm = viewModel(FakeCapture())

        vm.onInterrupted()

        assertEquals(RecordUiState.Idle, vm.state.value)
    }
}

/** 系统内录"全是静音"= 没抓到：不能把静音当成抓到了。 */
class RecordSystemAudioSilenceTest {

    /** 会走完 start/stop 的最小采集实现。 */
    private class IdleCapture : io.github.tancheng33.tingo.recording.AudioCapture {
        override fun start(file: File) {}
        override fun pause() {}
        override fun resume() {}
        override fun stop(): Long = 1_000
        override fun level(): Float = 0f
    }

    private val dispatcher = StandardTestDispatcher()
    private fun root(): File = Files.createTempDirectory("tingo-rec-silence").toFile()

    @Test
    fun silentSystemTrackIsReportedAsMissing() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        val vm = RecordViewModel(
            capture = IdleCapture(),
            meetingsRoot = root(),
            io = dispatcher,
            clock = { testScheduler.currentTime },
        )
        vm.start()
        vm.attachSystemAudio(SilentSystemAudio())

        // 录音期间有个 200 ms 的 ticker 在跑：不能用 advanceUntilIdle（它会一直推进虚拟时间），
        // 只推进到静音检查之后，再断言状态
        advanceTimeBy(RecordViewModel.SILENCE_CHECK_MS + 1_000)
        runCurrent()

        assertEquals(RecordViewModel.SystemAudioStatus.MISSING, vm.systemAudioStatus.value)
        vm.stop()
        advanceUntilIdle()
        Dispatchers.resetMain()
    }

    /** 完全不发声的系统音轨：会议软件禁止被抓时就是这个表现。 */
    private class SilentSystemAudio : io.github.tancheng33.tingo.recording.SystemAudioTrack {
        override fun start(file: File) {}
        override fun pause() {}
        override fun resume() {}
        override fun peakLevel(): Float = 0f
        override fun stop(): Long = 0
    }
}
