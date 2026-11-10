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

    private fun importer(decodes: Boolean = true, engine: FakeEnginePort = FakeEnginePort()): AudioImport =
        AudioImport(
        engine = engine,
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

    private fun viewModel(importer: AudioImport?, tasks: io.github.tancheng33.tingo.ui.tasks.TaskCenter? = null) =
        RecordViewModel(
            capture = NoCapture(),
            meetingsRoot = Files.createTempDirectory("tingo-meetings").toFile(),
            importing = importer,
            tasks = tasks,
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

    @Test
    fun successfulImportEndsWithTheMeetingIdAndNoRunningRow() = runTest(dispatcher) {
        val tasks = io.github.tancheng33.tingo.ui.tasks.TaskCenter()
        val vm = viewModel(importer(engine = FakeEnginePort()), tasks)

        vm.importAudio(source(), title = "周会", modifiedMs = 0)
        advanceUntilIdle()

        // 转写跑完就撤掉了，所以这里看的是"曾经登记过、且 id 是引擎给的"
        val importedId = vm.import.value.meetingId
        assertEquals("导入成功应给出新会议 id", "2026-11-07_1000_周会", importedId)
        assertTrue("结束后不该留在进行中", tasks.tasks.value.isEmpty())
    }

    @Test
    fun importTaskIsWithdrawnWhenItFinishes() = runTest(dispatcher) {
        val tasks = io.github.tancheng33.tingo.ui.tasks.TaskCenter()
        val vm = viewModel(importer(), tasks)

        vm.importAudio(source(), "周会", 0)
        advanceUntilIdle()

        assertTrue("导入结束后不该留在「进行中」", tasks.tasks.value.isEmpty())
    }

    @Test
    fun importTaskIsWithdrawnWhenDecodingFails() = runTest(dispatcher) {
        val tasks = io.github.tancheng33.tingo.ui.tasks.TaskCenter()
        val vm = viewModel(importer(decodes = false), tasks)

        vm.importAudio(source(), "坏文件", 0)
        advanceUntilIdle()

        assertEquals(RecordViewModel.ImportFailure.UNREADABLE, vm.import.value.failure)
        assertTrue("失败也要撤掉，否则首页永远挂着一条假的进行中", tasks.tasks.value.isEmpty())
    }

    @Test
    fun importIsRegisteredBeforeTranscriptionFinishes() = runTest(dispatcher) {
        val tasks = io.github.tancheng33.tingo.ui.tasks.TaskCenter()
        // 转写不主动收尾：停在"正在转写"这一步，才能看到中途的登记表
        val slow = object : Transcriber {
            override suspend fun transcribe(wav: File, outputDir: File, onProgress: (Float) -> Unit): Transcript {
                onProgress(0.5f)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val importer = AudioImport(
            engine = FakeEnginePort(),
            transcriber = slow,
            cacheDir = Files.createTempDirectory("tingo-record-import").toFile(),
            decoding = object : AudioDecoding {
                override fun toWav(source: File, target: File): File {
                    target.writeBytes(ByteArray(44))
                    return target
                }
            },
        )
        val vm = viewModel(importer, tasks)

        vm.importAudio(source(), title = "周会", modifiedMs = 0)
        advanceUntilIdle()

        val running = tasks.tasks.value.singleOrNull()
        assertTrue("转写途中必须能在「进行中」看到它", running != null)
        assertEquals(io.github.tancheng33.tingo.ui.tasks.TaskKind.IMPORT, running!!.kind)
        assertEquals("中途就该能点进会议", "2026-11-07_1000_周会", running.meetingId)
        assertEquals("转写进度要反映到那一行", 0.5f, running.fraction ?: 0f, 0.2f)
    }
}
