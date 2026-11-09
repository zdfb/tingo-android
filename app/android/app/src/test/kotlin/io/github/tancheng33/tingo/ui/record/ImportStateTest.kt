package io.github.tancheng33.tingo.ui.record

import io.github.tancheng33.tingo.importing.AudioDecoding
import io.github.tancheng33.tingo.importing.AudioImport
import io.github.tancheng33.tingo.recording.AudioCapture
import io.github.tancheng33.tingo.transcription.Transcript
import io.github.tancheng33.tingo.transcription.Transcriber
import io.github.tancheng33.tingo.testing.FakeEnginePort
import java.io.File
import java.nio.file.Files
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
 * 导入在录音页状态机旁的那一条流：进度、失败原因、重入。
 *
 * 失败原因必须分得开——"格式不支持"要用户换文件，"读不出来"可能是文件坏了，
 * 一句笼统的"导入失败"会让人反复试同一个文件。
 */
class ImportStateTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** 导入用例不该碰录音：任何一个方法被调到就说明接线错了。 */
    private class NoCapture : AudioCapture {
        override fun start(file: File): Nothing = error("导入用例不该录音")
        override fun pause(): Nothing = error("导入用例不该录音")
        override fun resume(): Nothing = error("导入用例不该录音")
        override fun stop(): Nothing = error("导入用例不该录音")
        override fun level(): Nothing = error("导入用例不该录音")
    }

    private class OkTranscriber : Transcriber {
        override suspend fun transcribe(wav: File, outputDir: File, onProgress: (Float) -> Unit): Transcript =
            Transcript(source = wav.name, durationMs = 0, sentences = emptyList())
    }

    private fun importer(decodes: Boolean = true): AudioImport = AudioImport(
        engine = FakeEnginePort(),
        transcriber = OkTranscriber(),
        cacheDir = Files.createTempDirectory("tingo-record-import").toFile(),
        decoding = object : AudioDecoding {
            override fun toWav(source: File, target: File): File {
                if (!decodes) throw IllegalArgumentException("这个文件里没有音频")
                target.writeBytes(ByteArray(44))
                return target
            }
        },
    )

    private fun viewModel(importer: AudioImport?) = RecordViewModel(
        capture = NoCapture(),
        meetingsRoot = Files.createTempDirectory("tingo-meetings").toFile(),
        importing = importer,
        io = dispatcher,
    )

    private fun source(): File = File.createTempFile("importsource", ".m4a").apply {
        writeBytes(ByteArray(8))
        deleteOnExit()
    }

    @Test
    fun successEmitsTheNewMeetingId() = runTest(dispatcher) {
        val vm = viewModel(importer())
        vm.importAudio(source(), "周会", 0)
        advanceUntilIdle()

        val state = vm.import.value
        assertNull("成功后不该报失败", state.failure)
        assertTrue("要给出会议 id", state.meetingId?.contains("周会") == true)
        assertNull("成功后进度要归零", state.progress)
    }

    @Test
    fun decodeFailureIsReportedAsUnreadable() = runTest(dispatcher) {
        val vm = viewModel(importer(decodes = false))
        vm.importAudio(source(), "坏文件", 0)
        advanceUntilIdle()

        assertEquals(RecordViewModel.ImportFailure.UNREADABLE, vm.import.value.failure)
    }

    @Test
    fun rejectedFileTypeIsADifferentFailureThanDecodeError() = runTest(dispatcher) {
        val vm = viewModel(importer())
        vm.importRejected()

        // 选到 docx 与选到坏 m4a 是两件事：用户要做的事不同
        assertEquals(RecordViewModel.ImportFailure.UNSUPPORTED, vm.import.value.failure)
    }

    @Test
    fun secondTapWhileImportingIsIgnored() = runTest(dispatcher) {
        val vm = viewModel(importer())
        vm.importAudio(source(), "第一次", 0)
        // 协程还没跑起来（进度已在启动前打上标记），这一次必须被忽略
        vm.importRejected()
        advanceUntilIdle()

        assertNull("不该被第二次点击覆盖成失败", vm.import.value.failure)
    }

    @Test
    fun consumeImportedClearsTheIdSoNavigationDoesNotRepeat() = runTest(dispatcher) {
        val vm = viewModel(importer())
        vm.importAudio(source(), "周会", 0)
        advanceUntilIdle()

        vm.consumeImported()

        assertNull(vm.import.value.meetingId)
    }
}
