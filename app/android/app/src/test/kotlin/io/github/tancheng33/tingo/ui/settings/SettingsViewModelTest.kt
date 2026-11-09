package io.github.tancheng33.tingo.ui.settings

import io.github.tancheng33.tingo.models.HttpFetcher
import io.github.tancheng33.tingo.models.ModelCatalog
import io.github.tancheng33.tingo.settings.AppSettings
import io.github.tancheng33.tingo.settings.KeyValueStore
import io.github.tancheng33.tingo.settings.LlmConfig
import io.github.tancheng33.tingo.models.ModelFile
import io.github.tancheng33.tingo.models.ModelStore
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 设置页的模型占用与删除：数字要准，删掉要真的腾出空间。 */
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val offline = object : HttpFetcher {
        override fun open(url: String, rangeStart: Long): HttpFetcher.FetchedStream = error("测试不该联网")
    }

    private fun store(): ModelStore = ModelStore(Files.createTempDirectory("tingo-settings").toFile(), offline)

    /** 每组的文件名不同——真实环境里识别模型与 GGUF 共用同一个目录，靠文件名区分。 */
    private fun catalog(vararg bytes: Long): ModelCatalog = catalog("m", *bytes)

    private fun catalog(prefix: String, vararg bytes: Long): ModelCatalog = ModelCatalog(
        files = bytes.mapIndexed { index, size ->
            ModelFile(
                id = "$prefix$index",
                fileName = "$prefix$index.bin",
                url = "https://example.invalid/$prefix$index.bin",
                bytes = size,
                sha256 = null,
            )
        },
    )

    /** 造一个"已就绪"的模型文件（体积对 + 有校验指纹）。 */
    private fun ready(store: ModelStore, file: ModelFile) {
        store.fileFor(file).writeBytes(ByteArray(file.bytes.toInt()))
        File(store.fileFor(file).parentFile, "${file.fileName}.verified").writeText(file.sha256 ?: "size-only")
    }

    private var root: File = Files.createTempDirectory("tingo-meetings").toFile()

    private fun viewModel(
        store: ModelStore,
        asr: ModelCatalog,
        settings: AppSettings = AppSettings(InMemoryStore()),
        meetingsRoot: File = root,
    ) = SettingsViewModel(store, asr, settings, meetingsRoot = meetingsRoot, io = dispatcher)

    /** 内存版的键值存储：只验证设置读写，不碰 Android 运行时。 */
    private class InMemoryStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    private fun meeting(id: String, file: String = "纪要.md", content: String = "# 一"): File {
        val dir = File(root, id).apply { mkdirs() }
        File(dir, file).writeText(content)
        return dir
    }

    @Test
    fun backupWritesEveryMeetingIntoTheZip() = runTest(dispatcher) {
        meeting("2026-11-01_0900_周会")
        meeting("2026-11-02_1000_复盘")
        val vm = viewModel(store(), catalog(1_000))
        val zip = File(Files.createTempDirectory("tingo-zip").toFile(), "library.zip")

        vm.backupTo { zip.outputStream() }
        advanceUntilIdle()

        val result = vm.state.value.libraryResult as LibraryOpResult.BackedUp
        assertEquals(2, result.meetings)
        assertTrue(zip.length() > 0)
    }

    @Test
    fun restoreReportsWhatItDid() = runTest(dispatcher) {
        meeting("2026-11-01_0900_周会", "纪要.md", "备份内容")
        val vm = viewModel(store(), catalog(1_000))
        val zip = File(Files.createTempDirectory("tingo-zip").toFile(), "library.zip")
        vm.backupTo { zip.outputStream() }
        advanceUntilIdle()

        // 换一个空的会议库来还原
        root = Files.createTempDirectory("tingo-meetings-2").toFile()
        val vm2 = viewModel(store(), catalog(1_000), meetingsRoot = root)
        vm2.restoreFrom { zip.inputStream() }
        advanceUntilIdle()

        val result = vm2.state.value.libraryResult as LibraryOpResult.Restored
        assertEquals(1, result.restored)
        assertEquals(0, result.skipped)
        assertEquals("备份内容", File(root, "2026-11-01_0900_周会/纪要.md").readText())
    }

    @Test
    fun restoreDoesNotOverwriteExistingMeetings() = runTest(dispatcher) {
        meeting("2026-11-01_0900_周会", "纪要.md", "备份内容")
        val vm = viewModel(store(), catalog(1_000))
        val zip = File(Files.createTempDirectory("tingo-zip").toFile(), "library.zip")
        vm.backupTo { zip.outputStream() }
        advanceUntilIdle()

        val vm2 = viewModel(store(), catalog(1_000))
        vm2.restoreFrom { zip.inputStream() }
        advanceUntilIdle()

        val result = vm2.state.value.libraryResult as LibraryOpResult.Restored
        assertEquals(0, result.restored)
        assertEquals(1, result.skipped)
        assertEquals("备份内容", File(root, "2026-11-01_0900_周会/纪要.md").readText())
    }

    @Test
    fun brokenZipReportsFailure() = runTest(dispatcher) {
        val vm = viewModel(store(), catalog(1_000))
        val broken = File(Files.createTempDirectory("tingo-zip").toFile(), "broken.zip").apply {
            writeText("这不是 zip")
        }

        vm.restoreFrom { broken.inputStream() }
        advanceUntilIdle()

        assertEquals(LibraryOpResult.Failed, vm.state.value.libraryResult)
    }

    @Test
    fun reportsNothingDownloadedInitially() = runTest(dispatcher) {
        val store = store()
        val vm = viewModel(store, catalog(1_000, 2_000))
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertTrue("没下过应显示为空", state.asr.isEmpty)
        assertEquals(2, state.asr.fileCount)
    }

    @Test
    fun reportsReadyGroupAndByteCount() = runTest(dispatcher) {
        val store = store()
        val asr = catalog(1_000, 2_000)
        asr.files.forEach { ready(store, it) }
        val vm = viewModel(store, asr)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.asr.isReady)
        assertEquals(2, state.asr.readyFiles)
        assertEquals(3_000L, state.asr.bytes)
    }

    @Test
    fun partialDownloadIsNeitherReadyNorEmpty() = runTest(dispatcher) {
        val store = store()
        val asr = catalog(1_000, 2_000)
        ready(store, asr.files.first())
        // 第二个只下了一半：没有校验指纹，也不完整
        store.fileFor(asr.files[1]).writeBytes(ByteArray(500))
        val vm = viewModel(store, asr)
        advanceUntilIdle()

        val group = vm.state.value.asr
        assertFalse(group.isReady)
        assertFalse(group.isEmpty)
        assertTrue("下了一半既不算就绪也不算空", !group.isReady && !group.isEmpty)
        assertEquals(1, group.readyFiles)
    }

    @Test
    fun deletingAsrFreesItsBytesAndKeepsLlm() = runTest(dispatcher) {
        val store = store()
        val asr = catalog(1_000, 2_000)
        val llm = catalog("extra", 3_000)
        asr.files.forEach { ready(store, it) }
        llm.files.forEach { ready(store, it) }
        val vm = viewModel(store, asr)
        advanceUntilIdle()

        vm.deleteAsr()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue("识别模型应被清空", state.asr.isEmpty)
        assertEquals(0L, state.asr.bytes)

    }

    @Test
    fun markerIsRemovedSoNextDownloadStartsClean() = runTest(dispatcher) {
        val store = store()
        val asr = catalog(1_000)
        ready(store, asr.files.single())
        val vm = viewModel(store, asr)
        advanceUntilIdle()

        vm.deleteAsr()
        advanceUntilIdle()

        val marker = File(store.fileFor(asr.files.single()).parentFile, "m0.bin.verified")
        assertFalse("校验指纹也要删掉，否则会留下'已就绪'的假象", marker.isFile)
        assertFalse(store.isReady(asr.files.single()))
    }

    @Test
    fun savingLlmConfigShowsUpInState() = runTest(dispatcher) {
        val settings = AppSettings(InMemoryStore())
        val vm = viewModel(store(), catalog(1_000), settings)
        advanceUntilIdle()

        vm.saveLlmConfig(LlmConfig(baseUrl = "https://api.example.com", apiKey = "sk-1", model = "m"))
        advanceUntilIdle()

        assertEquals("https://api.example.com", vm.state.value.llmConfig.baseUrl)
        assertEquals("m", settings.llm.model)
    }
}
