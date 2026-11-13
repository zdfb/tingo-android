package io.github.tancheng33.tingo.ui.detail

import io.github.tancheng33.tingo.R
import io.github.tancheng33.tingo.models.HttpFetcher
import io.github.tancheng33.tingo.models.ModelCatalog
import io.github.tancheng33.tingo.models.ModelFile
import io.github.tancheng33.tingo.models.ModelStore
import io.github.tancheng33.tingo.transcription.Transcript
import io.github.tancheng33.tingo.transcription.TranscriptSentence
import io.github.tancheng33.tingo.transcription.Transcriber
import java.io.File
import java.io.InputStream
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
class TranscriptionViewModelTest {
    @Test
    fun failedDualTrackMixDoesNotTranscribeOnlyTheMicrophone() = runTest(dispatcher) {
        val dir = meeting(withWav = false)
        io.github.tancheng33.tingo.recording.WavWriter(File(dir, "mic.wav"), 16_000).apply {
            append(ByteArray(3_200)); close()
        }
        val system = File(dir, "system.wav")
        io.github.tancheng33.tingo.recording.WavWriter(system, 48_000, channels = 2).apply {
            append(ByteArray(3_200)); close()
        }
        java.io.RandomAccessFile(system, "rw").use { it.seek(0); it.writeByte(0) }
        val store = ModelStore(Files.createTempDirectory("tingo-m").toFile(), FakeFetcher(1_000))
        store.download(catalog().files.single())
        val transcriber = FakeTranscriber()
        val vm = viewModel(dir, transcriber, store)
        vm.start()
        advanceUntilIdle()
        assertEquals(0, transcriber.calls)
        assertTrue(vm.state.value is TranscriptionUiState.Failed)
    }
    @Test
    fun automaticTranscriptionDoesNotDownloadMissingModel() = runTest(dispatcher) {
        val store = ModelStore(Files.createTempDirectory("tingo-m").toFile(), FakeFetcher(1_000))
        val transcriber = FakeTranscriber()
        val vm = viewModel(meeting(), transcriber, store)
        vm.start()
        advanceUntilIdle()
        assertEquals(1_000L, (vm.state.value as TranscriptionUiState.NeedModels).missingBytes)
        assertEquals(0, transcriber.calls)
        assertTrue(!store.isReady(catalog().files.single()))
    }
    @Test
    fun recognitionFailureKeepsDownloadedModels() = runTest(dispatcher) {
        val store = ModelStore(Files.createTempDirectory("tingo-m").toFile(), FakeFetcher(1_000))
        val model = catalog().files.single()
        store.download(model)
        val transcriber = FakeTranscriber(fail = true)
        val vm = viewModel(meeting(), transcriber, store)
        vm.start()
        advanceUntilIdle()
        assertEquals(1, transcriber.calls)
        assertTrue("识别失败不应删除或重新下载模型", store.isReady(model))
        assertTrue(vm.state.value is TranscriptionUiState.Failed)
    }
    @Test
    fun canceledTranscriptionCanBeAwaitedByBackgroundProcessing() = runTest(dispatcher) {
        val store = ModelStore(Files.createTempDirectory("tingo-m").toFile(), FakeFetcher(1_000))
        store.download(catalog().files.single())
        val transcriber = object : Transcriber {
            override suspend fun transcribe(wav: File, outputDir: File, onProgress: (Float) -> Unit): Transcript {
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val vm = viewModel(meeting(), transcriber, store)
        vm.start()
        testScheduler.runCurrent()
        vm.cancel()
        assertEquals(TranscriptionUiState.Idle, vm.awaitCompletion())
    }

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeFetcher(private val size: Int, private val fail: Boolean = false) : HttpFetcher {
        override fun open(url: String, rangeStart: Long): HttpFetcher.FetchedStream {
            if (fail) throw java.io.IOException("网络断开")
            val data = ByteArray(size) { 7 }
            val stream = object : InputStream() {
                private var i = rangeStart.toInt()
                override fun read(): Int = if (i >= data.size) -1 else data[i++].toInt()
            }
            return HttpFetcher.FetchedStream(stream, rangeStart > 0)
        }
    }

    private class FakeTranscriber(private val fail: Boolean = false) : Transcriber {
        var calls = 0
        override suspend fun transcribe(wav: File, outputDir: File, onProgress: (Float) -> Unit): Transcript {
            calls++
            onProgress(0.5f)
            if (fail) throw IllegalStateException("识别崩了")
            onProgress(1f)
            return Transcript(
                source = wav.name,
                durationMs = 1_000,
                sentences = listOf(TranscriptSentence(0, 0, 1_000, "你好。")),
            )
        }
    }

    private fun catalog(size: Int = 1_000) = ModelCatalog(
        listOf(ModelFile("paraformer", "a.onnx", "https://example.invalid/a", size.toLong())),
    )

    private fun meeting(withWav: Boolean = true): File {
        val dir = Files.createTempDirectory("tingo-meeting").toFile()
        if (withWav) {
            // 用 WavWriter 造一条真实可用的音轨：现在"有没有音频"要连头里的长度一起看
            io.github.tancheng33.tingo.recording.WavWriter(File(dir, "录音.wav"), 16_000).apply {
                append(ByteArray(3_200))
                close()
            }
        }
        return dir
    }

    private fun viewModel(
        dir: File,
        transcriber: Transcriber,
        store: ModelStore,
        catalog: ModelCatalog = catalog(),
    ) = TranscriptionViewModel(transcriber, store, catalog, dir, io = dispatcher)

    @Test
    fun prepareReportsMissingModels() = runTest(dispatcher) {
        val store = ModelStore(Files.createTempDirectory("tingo-m").toFile(), FakeFetcher(1_000))
        val vm = viewModel(meeting(), FakeTranscriber(), store)

        vm.prepare()
        advanceUntilIdle() // 读模型标记放到 IO 之后，prepare 变成异步

        val state = vm.state.value as TranscriptionUiState.NeedModels
        assertEquals(1_000L, state.missingBytes)
    }

    @Test
    fun prepareStaysIdleWhenModelsReady() = runTest(dispatcher) {
        val store = ModelStore(Files.createTempDirectory("tingo-m").toFile(), FakeFetcher(1_000))
        store.download(catalog().files.single())
        val vm = viewModel(meeting(), FakeTranscriber(), store)

        vm.prepare()
        advanceUntilIdle()

        assertEquals(TranscriptionUiState.Idle, vm.state.value)
    }

    @Test
    fun startDownloadsThenTranscribes() = runTest(dispatcher) {
        val store = ModelStore(Files.createTempDirectory("tingo-m").toFile(), FakeFetcher(1_000))
        val transcriber = FakeTranscriber()
        val vm = viewModel(meeting(), transcriber, store)

        vm.start(allowDownload = true)
        advanceUntilIdle()

        val state = vm.state.value as TranscriptionUiState.Done
        assertEquals(1, state.transcript.sentences.size)
        assertEquals(1, transcriber.calls)
        assertTrue(store.isReady(catalog().files.single()))
    }

    @Test
    fun downloadFailureStopsBeforeTranscribing() = runTest(dispatcher) {
        val store = ModelStore(
            Files.createTempDirectory("tingo-m").toFile(),
            FakeFetcher(1_000, fail = true),
        )
        val transcriber = FakeTranscriber()
        val vm = viewModel(meeting(), transcriber, store)

        vm.start(allowDownload = true)
        advanceUntilIdle()

        val state = vm.state.value as TranscriptionUiState.Failed
        assertEquals(R.string.err_model_download_failed, state.reasonRes)
        assertEquals("paraformer", state.detail)
        assertEquals(0, transcriber.calls)
    }

    @Test
    fun transcriptionFailureSurfaces() = runTest(dispatcher) {
        val store = ModelStore(Files.createTempDirectory("tingo-m").toFile(), FakeFetcher(1_000))
        store.download(catalog().files.single())
        val vm = viewModel(meeting(), FakeTranscriber(fail = true), store)

        vm.start()
        advanceUntilIdle()

        val state = vm.state.value as TranscriptionUiState.Failed
        assertEquals(R.string.err_transcribe_failed, state.reasonRes)
        assertEquals("识别崩了", state.detail)
    }

    @Test
    fun meetingWithoutRecordingFailsFast() = runTest(dispatcher) {
        val store = ModelStore(Files.createTempDirectory("tingo-m").toFile(), FakeFetcher(1_000))
        val vm = viewModel(meeting(withWav = false), FakeTranscriber(), store)

        vm.start()
        advanceUntilIdle()

        assertEquals(
            R.string.err_no_recording,
            (vm.state.value as TranscriptionUiState.Failed).reasonRes,
        )
    }

    @Test
    fun cancelReturnsToIdle() = runTest(dispatcher) {
        val store = ModelStore(Files.createTempDirectory("tingo-m").toFile(), FakeFetcher(1_000))
        store.download(catalog().files.single())
        val vm = viewModel(meeting(), FakeTranscriber(), store)

        vm.start()
        vm.cancel()
        advanceUntilIdle()

        assertEquals(TranscriptionUiState.Idle, vm.state.value)
    }
}
