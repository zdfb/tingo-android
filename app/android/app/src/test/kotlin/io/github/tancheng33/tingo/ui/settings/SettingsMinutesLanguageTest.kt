package io.github.tancheng33.tingo.ui.settings

import io.github.tancheng33.tingo.minutes.MinutesLanguage
import io.github.tancheng33.tingo.models.ModelCatalog
import io.github.tancheng33.tingo.models.ModelStore
import io.github.tancheng33.tingo.models.UrlFetcher
import io.github.tancheng33.tingo.settings.AppSettings
import io.github.tancheng33.tingo.settings.KeyValueStore
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** 设置页的纪要语言：改了就落盘，重进设置页还是那次的选择。 */
class SettingsMinutesLanguageTest {

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

    private fun viewModel(store: Store) = SettingsViewModel(
        store = ModelStore(Files.createTempDirectory("lang-models").toFile(), UrlFetcher()),
        catalog = ModelCatalog(emptyList()),
        settings = AppSettings(store),
        meetingsRoot = Files.createTempDirectory("lang-root").toFile(),
        io = dispatcher,
    )

    @Test
    fun defaultsToAuto() {
        assertEquals(MinutesLanguage.AUTO, viewModel(Store()).state.value.minutesLanguage)
    }

    @Test
    fun savingPersistsAndShowsUpInState() = runTest(dispatcher) {
        val store = Store()
        val vm = viewModel(store)
        advanceUntilIdle()

        vm.setMinutesLanguage(MinutesLanguage.EN)

        assertEquals(MinutesLanguage.EN, vm.state.value.minutesLanguage)
        assertEquals("en", store.values["minutes_language"])
    }

    @Test
    fun theChoiceSurvivesReopeningTheScreen() = runTest(dispatcher) {
        val store = Store()
        viewModel(store).setMinutesLanguage(MinutesLanguage.ZH)

        val reopened = viewModel(store)
        advanceUntilIdle()

        assertEquals(MinutesLanguage.ZH, reopened.state.value.minutesLanguage)
    }

    @Test
    fun unknownValuesFallBackToAuto() = runTest(dispatcher) {
        val store = Store()
        store.values["minutes_language"] = "klingon"
        val vm = viewModel(store)
        advanceUntilIdle()

        assertEquals(MinutesLanguage.AUTO, vm.state.value.minutesLanguage)
    }
}
