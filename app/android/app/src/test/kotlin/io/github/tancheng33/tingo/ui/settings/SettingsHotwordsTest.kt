package io.github.tancheng33.tingo.ui.settings

import io.github.tancheng33.tingo.models.ModelCatalog
import io.github.tancheng33.tingo.models.ModelStore
import io.github.tancheng33.tingo.models.UrlFetcher
import io.github.tancheng33.tingo.settings.AppSettings
import java.io.File
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
 * 设置页的热词与"拉取模型列表"。
 *
 * 盯的是"界面上的动作真的落到了引擎/服务上"，以及失败时用户能看到原因
 * ——这两件事在 JVM 上用假实现验，比真机跑一遍快得多。
 */
class SettingsHotwordsTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(port: HotwordsPort) = SettingsViewModel(
        store = ModelStore(java.nio.file.Files.createTempDirectory("tingo-models").toFile(), UrlFetcher()),
        catalog = ModelCatalog(emptyList()),
        settings = AppSettings(object : io.github.tancheng33.tingo.settings.KeyValueStore {
            private val values = mutableMapOf<String, String>()
            override fun getString(key: String): String? = values[key]
            override fun putString(key: String, value: String) {
                values[key] = value
            }
        }),
        hotwordsPort = port,
        meetingsRoot = java.nio.file.Files.createTempDirectory("tingo-meetings").toFile(),
        io = dispatcher,
    )

    /** 表单里正在填的配置：地址填了、模型名还没填——这正是"拉取"要服务的场景。 */
    private fun draft() = io.github.tancheng33.tingo.settings.LlmConfig(
        baseUrl = "https://api.example.com/v1",
        apiKey = "",
        model = "",
    )

    private class FakePort(
        private var words: List<String> = emptyList(),
        private val models: () -> List<String> = { emptyList() },
    ) : HotwordsPort {
        var saved: List<String>? = null

        override suspend fun load(): List<String> = words

        override suspend fun save(words: List<String>): List<String> {
            saved = words
            this.words = words
            return words
        }

        var askedWith: io.github.tancheng33.tingo.settings.LlmConfig? = null

        override suspend fun listModels(config: io.github.tancheng33.tingo.settings.LlmConfig): List<String> {
            askedWith = config
            return models()
        }
    }

    @Test
    fun loadsExistingHotwordsIntoTheSettingsState() = runTest(dispatcher) {
        val vm = viewModel(FakePort(words = listOf("星河计划", "Kafka")))
        advanceUntilIdle()

        assertEquals(listOf("星河计划", "Kafka"), vm.state.value.hotwords)
    }

    @Test
    fun savesHotwordsThroughTheEngine() = runTest(dispatcher) {
        val port = FakePort()
        val vm = viewModel(port)
        advanceUntilIdle()

        vm.saveHotwords(listOf("林舟", "星河计划"))
        advanceUntilIdle()

        assertEquals(listOf("林舟", "星河计划"), port.saved)
        // 回填的是引擎归一化后的结果，界面不该显示未保存的原文
        assertEquals(listOf("林舟", "星河计划"), vm.state.value.hotwords)
    }

    @Test
    fun saveFailureIsSurfacedInsteadOfPretendingItWorked() = runTest(dispatcher) {
        val port = object : HotwordsPort {
            override suspend fun load(): List<String> = listOf("旧词")
            override suspend fun save(words: List<String>): List<String> = error("磁盘满了")
            override suspend fun listModels(config: io.github.tancheng33.tingo.settings.LlmConfig) = emptyList<String>()
        }
        val vm = viewModel(port)
        advanceUntilIdle()

        vm.saveHotwords(listOf("新词"))
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue("保存失败要能被界面看见", state.hotwordsFailed)
        // 词表保持原样：没写进去的东西不能显示成已生效
        assertEquals(listOf("旧词"), state.hotwords)
    }

    @Test
    fun fetchUsesTheDraftConfigNotTheSavedOne() = runTest(dispatcher) {
        val port = FakePort(models = { listOf("m1") })
        val vm = viewModel(port)
        advanceUntilIdle()

        vm.fetchModels(draft())
        advanceUntilIdle()

        assertEquals("https://api.example.com/v1", port.askedWith?.baseUrl)
        assertEquals("", port.askedWith?.model)
    }

    @Test
    fun modelListShowsWhatTheServiceReturned() = runTest(dispatcher) {
        val vm = viewModel(FakePort(models = { listOf("deepseek-chat", "deepseek-reasoner") }))
        advanceUntilIdle()

        vm.fetchModels(draft())
        advanceUntilIdle()

        val state = vm.state.value.models as ModelListState.Ready
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), state.models)
    }

    @Test
    fun fetchFailureKeepsTheReasonVisible() = runTest(dispatcher) {
        val vm = viewModel(FakePort(models = { throw IllegalStateException("（HTTP 401）invalid api key") }))
        advanceUntilIdle()

        vm.fetchModels(draft())
        advanceUntilIdle()

        val failed = vm.state.value.models as ModelListState.Failed
        assertTrue("失败原因要带给用户：${failed.reason}", failed.reason.contains("401"))
    }
}
