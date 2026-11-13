package io.github.tancheng33.tingo.ui.record

import io.github.tancheng33.tingo.R
import io.github.tancheng33.tingo.recording.AudioCapture
import io.github.tancheng33.tingo.recording.WavWriter
import io.github.tancheng33.tingo.recording.LiveTranscriber
import io.github.tancheng33.tingo.recording.SystemAudioTrack
import io.github.tancheng33.tingo.transcription.LiveAsr
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordViewModelTest {
    @Test
    fun stopFailureStillStopsSystemTrackAndPreservesRecoveryNotice() = runTest(dispatcher) {
        val root = root()
        val mic = FakeCapture()
        val system = FakeSystemTrack()
        val vm = RecordViewModel(mic, root, io = dispatcher, scope = { backgroundScope })
        vm.start()
        vm.attachSystemAudio(system)
        mic.stopFailure = IllegalStateException("close failed")
        vm.stop()
        assertTrue(vm.state.value is RecordUiState.Failed)
        assertEquals(1, system.stopped)
        assertEquals(listOf(mic.started!!.parentFile!!.name), io.github.tancheng33.tingo.recording.InterruptedRecordings.pendingRecoveryUnder(root))
    }
    @Test
    fun microphoneFailureStopsRecordingAndPreservesRecoveryNotice() = runTest(dispatcher) {
        val root = root()
        val capture = FakeCapture()
        val vm = RecordViewModel(capture, root, io = dispatcher, scope = { backgroundScope })
        vm.start()
        capture.failure = IllegalStateException("microphone disconnected")
        advanceTimeBy(400)
        runCurrent()
        assertTrue(vm.state.value is RecordUiState.Failed)
        assertEquals(1, capture.stopped)
        assertEquals(listOf(capture.started!!.parentFile!!.name), io.github.tancheng33.tingo.recording.InterruptedRecordings.pendingRecoveryUnder(root))
    }
    @Test
    fun startingWhilePausedDoesNotReplaceRecording() = runTest(dispatcher) {
        val root = root()
        val capture = FakeCapture()
        val vm = RecordViewModel(capture, root, io = dispatcher, scope = { backgroundScope })
        vm.start()
        vm.pause()
        val first = capture.started
        vm.start()
        assertEquals(first, capture.started)
        assertTrue(vm.state.value is RecordUiState.Paused)
        assertEquals(1, root.listFiles()!!.size)
        vm.stop()
    }
    @Test
    fun quickNewMeetingDoesNotLosePreviousCompletion() = runTest(dispatcher) {
        val vm = RecordViewModel(FakeCapture(), root(), io = dispatcher, scope = { backgroundScope })
        val completed = mutableListOf<io.github.tancheng33.tingo.recording.RecordingController.CompletedRecording>()
        val controller = io.github.tancheng33.tingo.recording.RecordingController(vm, backgroundScope, {}, completed::add)
        controller.configureNextRecording(transcribe = true, minutes = false)
        vm.start()
        vm.stop()
        val firstId = (vm.state.value as RecordUiState.Finished).meetingId
        vm.prepareNewMeeting()
        controller.configureNextRecording(transcribe = false, minutes = false)
        vm.start()
        runCurrent()
        assertEquals(listOf(firstId), completed.map { it.meetingId })
        assertTrue(completed.single().transcribe)
        vm.stop()
        controller.close()
    }
    @Test
    fun finishedMeetingCanReturnToNewRecordingSetup() = runTest(dispatcher) {
        val vm = RecordViewModel(FakeCapture(), root(), io = dispatcher, scope = { backgroundScope })
        vm.start()
        vm.stop()
        assertTrue(vm.state.value is RecordUiState.Finished)
        vm.prepareNewMeeting()
        assertEquals(RecordUiState.Idle, vm.state.value)
    }
    @Test
    fun systemAudioFailureIsShownWhileMicrophoneKeepsRecording() = runTest(dispatcher) {
        val mic = FakeCapture()
        val system = FakeSystemTrack()
        val vm = RecordViewModel(mic, root(), io = dispatcher, scope = { backgroundScope })
        vm.start()
        vm.attachSystemAudio(system)
        system.failure = IllegalStateException("capture stopped")
        advanceTimeBy(400)
        runCurrent()
        assertEquals(RecordViewModel.SystemAudioStatus.MISSING, vm.systemAudioStatus.value)
        assertTrue(vm.state.value is RecordUiState.Recording)
        assertEquals(0, mic.stopped)
        vm.stop()
    }

    @Test
    fun systemTrackIsAlignedWhenAttachedLateAndAfterPause() = runTest(dispatcher) {
        val capture = FakeCapture()
        val system = FakeSystemTrack()
        val vm = RecordViewModel(capture, root(), io = dispatcher, scope = { backgroundScope })
        vm.start()
        capture.emit(ByteArray(32_000))
        vm.attachSystemAudio(system)
        assertEquals(1_000L, system.startedAtMs)

        vm.pause()
        vm.resume()
        assertEquals(listOf(1_000L), system.alignedAtMs)
        vm.stop()
    }
    @Test
    fun failedLiveModelLoadingWarnsWithoutStoppingRecording() = runTest(dispatcher) {
        val vm = RecordViewModel(FakeCapture(), root(), io = dispatcher, scope = { backgroundScope },
            liveTranscriber = { throw IllegalStateException("model failed") })
        vm.start()
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!vm.liveTranscriptIncomplete.value && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(vm.liveTranscriptIncomplete.value)
        assertTrue(vm.state.value is RecordUiState.Recording)
        vm.stop()
    }
    @Test
    fun processingKeepsForegroundLeaseUntilMinutesFinish() = runTest(dispatcher) {
        val events = mutableListOf<String>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val processor = io.github.tancheng33.tingo.recording.AutoProcessor(
            backgroundScope,
            transcribe = { true },
            minutes = { release.await() },
            onStarted = { events.add("start") },
            onFinished = { events.add("stop") },
        )
        processor.submit(io.github.tancheng33.tingo.recording.RecordingController.CompletedRecording("meeting", true, true))
        runCurrent()
        assertEquals(listOf("start"), events)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("start", "stop"), events)
    }
    @Test
    fun processingWaitsUntilAutomaticSilenceTrimIsComplete() = runTest(dispatcher) {
        val capture = FakeCapture()
        val vm = RecordViewModel(capture, root(), io = dispatcher, scope = { backgroundScope })
        vm.start()
        capture.emit(ByteArray(32_000 * 2))
        vm.stop(keepMs = 1_000)
        val id = (vm.state.value as RecordUiState.Finished).meetingId
        vm.awaitFinalization(id)
        val audio = io.github.tancheng33.tingo.recording.RecordingTracks.find(capture.started!!.parentFile)!!
        assertEquals(1_000L, io.github.tancheng33.tingo.transcription.WavReader.format(audio).durationMs)
    }
    @Test
    fun completedRecordingStartsTranscriptionThenMinutesWithoutScreen() = runTest(dispatcher) {
        val events = mutableListOf<String>()
        val processor = io.github.tancheng33.tingo.recording.AutoProcessor(
            backgroundScope,
            transcribe = { id -> events.add("transcribe:$id"); true },
            minutes = { id -> events.add("minutes:$id") },
        )
        val vm = RecordViewModel(FakeCapture(), root(), io = dispatcher, scope = { backgroundScope })
        val controller = io.github.tancheng33.tingo.recording.RecordingController(vm, backgroundScope, {}, processor::submit)
        controller.configureNextRecording(transcribe = true, minutes = true)
        vm.start()
        runCurrent()
        vm.stop()
        runCurrent()
        val id = (vm.state.value as RecordUiState.Finished).meetingId
        assertEquals(listOf("transcribe:$id", "minutes:$id"), events)
        controller.close()
    }

    @Test
    fun failedAutomaticTranscriptionDoesNotGenerateMinutes() = runTest(dispatcher) {
        val events = mutableListOf<String>()
        val processor = io.github.tancheng33.tingo.recording.AutoProcessor(
            backgroundScope,
            transcribe = { events.add("transcribe"); false },
            minutes = { events.add("minutes") },
        )
        processor.submit(io.github.tancheng33.tingo.recording.RecordingController.CompletedRecording("meeting", true, true))
        runCurrent()
        assertEquals(listOf("transcribe"), events)
    }
    @Test
    fun backgroundCompletionUsesTheChoicesForThatRecording() = runTest(dispatcher) {
        val capture = FakeCapture()
        val vm = RecordViewModel(capture, root(), io = dispatcher, scope = { backgroundScope })
        val completed = mutableListOf<io.github.tancheng33.tingo.recording.RecordingController.CompletedRecording>()
        val controller = io.github.tancheng33.tingo.recording.RecordingController(vm, backgroundScope, {}, completed::add)
        controller.configureNextRecording(transcribe = true, minutes = false)
        vm.start()
        runCurrent()
        io.github.tancheng33.tingo.recording.RecordingKeepAlive.onStopRequested()
        runCurrent()
        assertEquals(1, completed.size)
        assertEquals(true, completed.single().transcribe)
        assertEquals(false, completed.single().minutes)
        controller.close()
    }
    @Test
    fun notificationCanStopRecordingWithoutAVisibleScreen() = runTest(dispatcher) {
        val capture = FakeCapture()
        val vm = RecordViewModel(capture, root(), io = dispatcher, scope = { backgroundScope })
        val controller = io.github.tancheng33.tingo.recording.RecordingController(vm, backgroundScope, {})
        vm.start()
        runCurrent()
        io.github.tancheng33.tingo.recording.RecordingKeepAlive.onStopRequested()
        assertTrue(vm.state.value is RecordUiState.Finished)
        assertEquals(1, capture.stopped)
        controller.close()
    }
    @Test
    fun clearingRecordingViewModelStopsBothAudioTracks() = runTest(dispatcher) {
        val mic = FakeCapture()
        val system = FakeSystemTrack()
        val vm = RecordViewModel(mic, root(), io = dispatcher, scope = { backgroundScope })
        val store = androidx.lifecycle.ViewModelStore()
        val provider = androidx.lifecycle.ViewModelProvider.create(store, object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = vm as T
        })
        provider[RecordViewModel::class.java]
        vm.start()
        vm.attachSystemAudio(system)
        store.clear()
        assertEquals(1, mic.stopped)
        assertEquals(1, system.stopped)
    }
    @Test
    fun recordingStartsWithoutWaitingForLiveModelLoading() = runTest(dispatcher) {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val returned = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.CountDownLatch(1)
        val vm = RecordViewModel(FakeCapture(), root(), io = dispatcher, scope = { backgroundScope },
            liveTranscriber = { callback ->
                entered.countDown()
                release.await()
                LiveTranscriber(asr = FakeLiveAsr("文本"), releaseModels = { released.countDown() }, onSentence = callback)
            })
        val starter = Thread { vm.start(); returned.countDown() }
        starter.start()
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("录音开始不能等待模型加载", returned.await(1, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(vm.state.value is RecordUiState.Recording)
            vm.stop()
        } finally {
            release.countDown()
            starter.join(2000)
        }
        assertTrue("结束录音后加载完成的模型应被释放", released.await(2, java.util.concurrent.TimeUnit.SECONDS))
    }
    @Test
    fun liveSpeakerNumbersAreSharedAcrossAudioTracks() = runTest(dispatcher) {
        val mic = FakeCapture()
        val system = FakeSystemTrack()
        var index = 0
        val vm = RecordViewModel(mic, root(), io = dispatcher, scope = { backgroundScope },
            liveTranscriber = { callback ->
                val speaker = ++index
                val asr = object : LiveAsr {
                    var ready = false
                    override fun acceptWindow(window: FloatArray) { ready = true }
                    override fun hasSegment() = ready
                    override fun popSegment(): FloatArray { ready = false; return FloatArray(16000) }
                    override fun flush() = Unit
                    override fun recognize(samples: FloatArray) = "音轨$speaker"
                    override fun speakerEmbedding(samples: FloatArray) = floatArrayOf(1f, 0f)
                }
                LiveTranscriber(asr = asr, onSentence = callback)
            })
        vm.start()
        vm.attachSystemAudio(system)
        mic.emitSamples(FloatArray(512))
        system.emitSamples(FloatArray(512))
        val deadline = System.nanoTime() + 3_000_000_000L
        while (vm.liveEntries.size < 2 && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(listOf(1, 1), vm.liveEntries.map { it.speaker })
        vm.stop()
    }

    private val dispatcher = StandardTestDispatcher()
    private var now = 0L

    private class FakeCapture(private val durationMs: Long = 1_000) : AudioCapture {
        var failure: Throwable? = null
        override fun lastFailure(): Throwable? = failure
        var started: File? = null
        var pauses = 0
        var resumes = 0
        var stopped = 0
        var failWith: Exception? = null
        var stopFailure: Exception? = null
        private var listener: ((FloatArray) -> Unit)? = null
        override fun setSamplesListener(listener: ((FloatArray) -> Unit)?) { this.listener = listener }
        fun emitSamples(samples: FloatArray) { listener?.invoke(samples) }

        private var writer: WavWriter? = null
        override fun durationMs(): Long = writer?.durationMs() ?: durationMs

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
            stopFailure?.let { throw it }
            return durationMs
        }

        /** 电平：用例要模拟"没人说话"时调低 */
        var level: Float = 0.5f

        override fun level(): Float = level
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun root(): File = Files.createTempDirectory("tingo-record").toFile()

    private fun TestScope.viewModel(capture: FakeCapture, root: File = root()) =
        RecordViewModel(capture, root, clock = { now }, io = dispatcher, scope = { backgroundScope })

    @Test
    fun recordingRequiresTheSpeechModel() = runTest(dispatcher) {
        val capture = FakeCapture()
        val vm = RecordViewModel(capture, root(), modelReady = { false }, io = dispatcher, scope = { backgroundScope })

        vm.start()

        assertEquals(R.string.err_model_required, (vm.state.value as RecordUiState.Failed).reasonRes)
        assertNull(capture.started)
    }

    @Test
    fun failedHighlightSaveKeepsRecordingRunning() = runTest(dispatcher) {
        val root = root()
        val vm = viewModel(FakeCapture(), root)
        vm.start()
        val meeting = root.listFiles()!!.single()
        File(meeting, "highlights.json").mkdir()
        vm.markHighlight()
        assertTrue(vm.state.value is RecordUiState.Recording)
        assertTrue(vm.highlights.value.isEmpty())
        assertTrue(vm.highlightSaveFailed.value)
        vm.stop()
    }

    @Test
    fun recordingMarkersPersistAtElapsedTimeExcludingPauses() = runTest(dispatcher) {
        val root = root()
        val vm = viewModel(FakeCapture(), root)
        vm.start()
        now = 2_000
        vm.markHighlight()
        vm.pause()
        now = 10_000
        vm.markHighlight()
        vm.resume()
        now = 11_000
        vm.markHighlight()
        vm.stop()

        assertEquals(listOf(2_000L, 2_000L, 3_000L), vm.highlights.value)
        val meeting = root.listFiles()!!.single()
        assertEquals(listOf(2_000L, 2_000L, 3_000L), io.github.tancheng33.tingo.recording.RecordingHighlights.read(meeting))
    }

    @Test
    fun bothAudioTracksProduceLiveSentences() = runTest(dispatcher) {
        val mic = FakeCapture()
        val system = FakeSystemTrack()
        var created = 0
        val vm = RecordViewModel(
            mic, root(), io = dispatcher, scope = { backgroundScope },
            liveTranscriber = { callback ->
                val label = if (++created == 1) "mic" else "system"
                LiveTranscriber(asr = FakeLiveAsr(label), onSentence = callback)
            },
        )
        vm.start()
        vm.attachSystemAudio(system)
        mic.emitSamples(FloatArray(4096) { 0.5f })
        system.emitSamples(FloatArray(4096) { 0.5f })
        val deadline = System.nanoTime() + 3_000_000_000L
        while (vm.liveEntries.size < 2 && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(vm.liveEntries.map { it.text }.containsAll(listOf("mic", "system")))
        vm.stop()
    }

    @Test
    fun systemSamplesKeepTheirPositionAcrossCaptureChunks() {
        val converter = io.github.tancheng33.tingo.recording.SystemPcmConverter()
        val pcm = byteArrayOf(
            0, 16, 0, 16, 0, 32, 0, 32, 0, 48, 0, 48,
            0, 64, 0, 64, 0, 80, 0, 80, 0, 96, 0, 96,
        )
        val first = converter.convert(pcm.copyOfRange(0, 8), 8)
        val second = converter.convert(pcm.copyOfRange(8, 24), 16)
        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertEquals(0.125f, first[0], 0.0001f)
        assertEquals(0.5f, second[0], 0.0001f)
    }

    private class FakeSystemTrack : SystemAudioTrack {
        var stopped = 0
        var failure: Throwable? = null
        var startedAtMs = -1L
        val alignedAtMs = mutableListOf<Long>()
        override fun lastFailure(): Throwable? = failure
        private var listener: ((FloatArray) -> Unit)? = null
        override fun setSamplesListener(listener: ((FloatArray) -> Unit)?) { this.listener = listener }
        fun emitSamples(samples: FloatArray) { listener?.invoke(samples) }
        override fun start(file: File) {}
        override fun startAligned(file: File, positionMs: () -> Long) { startedAtMs = positionMs() }
        override fun alignTo(durationMs: Long) { alignedAtMs += durationMs }
        override fun pause() {}
        override fun resume() {}
        override fun peakLevel() = 0.5f
        override fun stop(): Long { stopped++; return 0L }
    }

    private class FakeLiveAsr(private val text: String) : LiveAsr {
        private var available = false
        override fun acceptWindow(window: FloatArray) { available = true }
        override fun hasSegment() = available
        override fun popSegment(): FloatArray { available = false; return FloatArray(4000) }
        override fun flush() {}
        override fun recognize(samples: FloatArray) = text
    }

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
        val mixed = File(dir, io.github.tancheng33.tingo.recording.TrackMerger.MIXED)

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
        assertEquals(listOf(meeting.name), io.github.tancheng33.tingo.recording.InterruptedRecordings.pendingRecoveryUnder(root))
    }

    @Test
    fun interruptionIsIgnoredWhenNothingIsRecording() = runTest(dispatcher) {
        val vm = viewModel(FakeCapture())

        vm.onInterrupted()

        assertEquals(RecordUiState.Idle, vm.state.value)
    }
    @Test
    fun autoStopsAfterConfiguredSilenceAndKeepsTheTail() = runTest(dispatcher) {
        val capture = FakeCapture()
        val root = root()
        val vm = RecordViewModel(
            capture,
            root,
            clock = { now },
            autoStopSilenceMin = { 1 },
            io = dispatcher,
            scope = { backgroundScope },
        )
        vm.start()
        // 真的写 65 秒的音频进去：只有头没有数据的 wav 会让截断变成空操作，断言就成了摆设
        capture.emit(ByteArray(16_000 * 2 * 65) { 0 })
        // 先"说" 5 秒，然后一直安静
        now = 5_000
        advanceTimeBy(500)
        capture.level = 0f
        now = 5_000L + 61_000
        advanceTimeBy(500)
        advanceUntilIdle()

        // 到点自动结束，并告诉用户为什么
        assertEquals(1, vm.autoStoppedAfter.value)
        assertTrue("应当已经结束录音", vm.state.value is RecordUiState.Finished)
        assertEquals(1, capture.stopped)
        // 静音的尾巴要被截掉：只留到最后有声之后 30 秒
        val dir = File(root, (vm.state.value as RecordUiState.Finished).meetingId)
        val mic = File(dir, "mic.wav")
        val format = io.github.tancheng33.tingo.transcription.WavReader.format(mic)
        // 只留到最后有声（5 秒）之后 30 秒 → 35 秒；整段是 65 秒
        assertTrue("尾巴没截干净：${format.durationMs}", format.durationMs in 34_000..36_000)
        // 混出来的 录音.wav 也必须是截过的长度（截断与混音的先后顺序曾经是错的）
        val merged = io.github.tancheng33.tingo.transcription.WavReader.format(File(dir, "录音.wav"))
        assertTrue("录出来的 录音.wav 还是整段长度：${merged.durationMs}", merged.durationMs in 34_000..36_000)
        vm.consumeAutoStopNotice()
        assertNull(vm.autoStoppedAfter.value)
    }

    @Test
    fun silenceDisabledNeverAutoStops() = runTest(dispatcher) {
        val capture = FakeCapture()
        val vm = RecordViewModel(
            capture,
            root(),
            clock = { now },
            autoStopSilenceMin = { 0 },
            io = dispatcher,
            scope = { backgroundScope },
        )
        vm.start()
        capture.level = 0f
        now = 3 * 60 * 60 * 1_000L
        advanceTimeBy(500)

        assertTrue("关掉自动结束就该一直录", vm.state.value is RecordUiState.Recording)
        assertNull(vm.autoStoppedAfter.value)
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
