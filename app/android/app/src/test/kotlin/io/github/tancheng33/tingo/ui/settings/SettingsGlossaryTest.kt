package io.github.tancheng33.tingo.ui.settings

import io.github.tancheng33.tingo.engine.GlossaryTerm
import io.github.tancheng33.tingo.engine.StorageStats
import io.github.tancheng33.tingo.models.ModelCatalog
import io.github.tancheng33.tingo.models.ModelStore
import io.github.tancheng33.tingo.models.UrlFetcher
import io.github.tancheng33.tingo.settings.AppSettings
import io.github.tancheng33.tingo.settings.KeyValueStore
import io.github.tancheng33.tingo.settings.LlmConfig
import java.nio.file.Files
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
 * 术语文与热词的关系。
 *
 * 桌面端在纪要里给每个术语一个"加进热词"；手机上集中一处。两种错法都不显眼：
 * 术语读不出来（界面空着，用户以为没有术语），或者同一个词被反复加进热词。
 */
class SettingsGlossaryTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Store : KeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    private class Port(
        private val terms: List<GlossaryTerm>,
        private var words: List<String> = emptyList(),
    ) : HotwordsPort {
        var saves = 0

        override suspend fun load(): List<String> = words

        override suspend fun save(words: List<String>): List<String> {
            saves++
            this.words = words
            return words
        }

        override suspend fun listModels(config: LlmConfig): List<String> = emptyList()

        override suspend fun glossary(): List<GlossaryTerm> = terms
    }

    private fun viewModel(port: Port) = SettingsViewModel(
        store = ModelStore(Files.createTempDirectory("glossary-models").toFile(), UrlFetcher()),
        catalog = ModelCatalog(emptyList()),
        settings = AppSettings(Store()),
        hotwordsPort = port,
        storageStats = { StorageStats(0u, 0uL, 0uL, 0uL, emptyList()) },
        meetingsRoot = Files.createTempDirectory("glossary-root").toFile(),
        io = dispatcher,
    )

    @Test
    fun glossaryLoadsFromTheEngine() = runTest(dispatcher) {
        val port = Port(listOf(GlossaryTerm("星河计划", emptyList()), GlossaryTerm("林舟", listOf("林州"))))
        val vm = viewModel(port)
        advanceUntilIdle()

        assertEquals(listOf("星河计划", "林舟"), vm.state.value.glossary.map { it.term })
    }

    @Test
    fun tappingATermAddsItToHotwords() = runTest(dispatcher) {
        val port = Port(listOf(GlossaryTerm("星河计划", emptyList())))
        val vm = viewModel(port)
        advanceUntilIdle()

        vm.addHotword("星河计划")
        advanceUntilIdle()

        assertEquals(listOf("星河计划"), vm.state.value.hotwords)
        assertEquals(1, port.saves)
    }

    @Test
    fun addingTheSameTermTwiceKeepsOneEntry() = runTest(dispatcher) {
        val port = Port(listOf(GlossaryTerm("星河计划", emptyList())))
        val vm = viewModel(port)
        advanceUntilIdle()

        vm.addHotword("星河计划")
        advanceUntilIdle()
        vm.addHotword("星河计划")
        advanceUntilIdle()

        assertEquals("同一个词不该重复出现", 1, vm.state.value.hotwords.size)
    }

    @Test
    fun matchingIgnoresCase() = runTest(dispatcher) {
        val port = Port(emptyList(), words = listOf("Kafka"))
        val vm = viewModel(port)
        advanceUntilIdle()

        vm.addHotword("kafka")
        advanceUntilIdle()

        assertEquals(listOf("Kafka"), vm.state.value.hotwords)
    }

    @Test
    fun blankTermsAreIgnored() = runTest(dispatcher) {
        val port = Port(emptyList())
        val vm = viewModel(port)
        advanceUntilIdle()

        vm.addHotword("   ")
        advanceUntilIdle()

        assertTrue(vm.state.value.hotwords.isEmpty())
        assertEquals(0, port.saves)
    }
}
