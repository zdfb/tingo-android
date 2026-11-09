package io.github.tancheng33.tingo.ui.onboard

import io.github.tancheng33.tingo.models.ModelCatalog
import io.github.tancheng33.tingo.models.ModelStore
import io.github.tancheng33.tingo.models.UrlFetcher
import io.github.tancheng33.tingo.settings.AppSettings
import io.github.tancheng33.tingo.settings.KeyValueStore
import io.github.tancheng33.tingo.testing.FakeEnginePort
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 新手引导的流程与非打扰原则。
 *
 * 最要紧的一条：**已经用过的人不该在升级后被拦一次**。所以"是否自动弹"同时看
 * 偏好里的标记和库里有没有会议。
 */
class OnboardingTest {

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

    private fun viewModel(
        settings: AppSettings = AppSettings(Store()),
        engine: FakeEnginePort = FakeEnginePort(),
    ) = OnboardingViewModel(
        settings = settings,
        store = ModelStore(Files.createTempDirectory("onboard-models").toFile(), UrlFetcher()),
        catalog = ModelCatalog(emptyList()),
        // 真实现要读 app assets（只能在真机跑）；这里只验流程，用替身
        demo = object : io.github.tancheng33.tingo.importing.DemoInstaller {
            override fun install(english: Boolean): String? {
                engine.demoInstalls += english
                return "2026-10-28_1400_示例"
            }
        },
        io = dispatcher,
    )

    @Test
    fun showsOnlyForANewUserWithAnEmptyLibrary() {
        val settings = AppSettings(Store())
        val vm = viewModel(settings)

        assertTrue("新用户空库该引导", vm.shouldOnboard(hasMeetings = false))
        assertFalse("已经有会议的老用户不该被拦", vm.shouldOnboard(hasMeetings = true))

        settings.onboarded = true
        assertFalse("走过引导就不再自动弹", vm.shouldOnboard(hasMeetings = false))
    }

    @Test
    fun stepsFollowTheDesktopOrder() {
        val vm = viewModel()

        assertEquals(
            listOf(
                OnboardingViewModel.Step.HELLO,
                OnboardingViewModel.Step.MODEL,
                OnboardingViewModel.Step.PERMISSION,
                OnboardingViewModel.Step.LLM,
                OnboardingViewModel.Step.DEMO,
            ),
            vm.steps,
        )
        vm.next()
        assertEquals(OnboardingViewModel.Step.MODEL, vm.state.value.step)
        vm.back()
        assertEquals(OnboardingViewModel.Step.HELLO, vm.state.value.step)
    }

    @Test
    fun skippingMarksItDoneSoItNeverBlocksAgain() {
        val settings = AppSettings(Store())
        val vm = viewModel(settings)

        vm.skip()

        assertTrue("跳过要记下来", settings.onboarded)
        assertTrue("界面据此收起引导", vm.state.value.finished)
    }

    @Test
    fun lastStepMovesOnMeansFinished() {
        val settings = AppSettings(Store())
        val vm = viewModel(settings)
        repeat(vm.steps.size - 1) { vm.next() }
        assertEquals(OnboardingViewModel.Step.DEMO, vm.state.value.step)

        // 最后一步的按钮是"开始第一场会议"：点了就走完
        vm.next()

        assertTrue(settings.onboarded)
        assertTrue(vm.state.value.finished)
    }

    @Test
    fun installingTheSampleMeetingHandsBackItsId() = runTest(dispatcher) {
        val engine = FakeEnginePort()
        val vm = viewModel(engine = engine)

        vm.installDemo(english = false)
        advanceUntilIdle()

        assertEquals(listOf(false), engine.demoInstalls)
        assertTrue("装好要把会议 id 交出去", vm.state.value.demoMeetingId?.contains("示例") == true)
        vm.consumeDemo()
        assertNull("跳过跳转后不该重复跳", vm.state.value.demoMeetingId)
    }

    @Test
    fun englishUiInstallsTheEnglishSample() = runTest(dispatcher) {
        val engine = FakeEnginePort()
        val vm = viewModel(engine = engine)

        vm.installDemo(english = true)
        advanceUntilIdle()

        assertEquals(listOf(true), engine.demoInstalls)
    }

    @Test
    fun restartClearsTheFlagAndGoesBackToTheStart() {
        val settings = AppSettings(Store())
        val vm = viewModel(settings)
        vm.skip()

        vm.restart()

        assertFalse("重开要重新弹", settings.onboarded)
        assertEquals(OnboardingViewModel.Step.HELLO, vm.state.value.step)
        assertFalse(vm.state.value.finished)
    }
}
