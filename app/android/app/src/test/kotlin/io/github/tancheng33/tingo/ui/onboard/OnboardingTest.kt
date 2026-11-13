package io.github.tancheng33.tingo.ui.onboard

import io.github.tancheng33.tingo.models.ModelCatalog
import io.github.tancheng33.tingo.models.ModelStore
import io.github.tancheng33.tingo.models.ModelFile
import io.github.tancheng33.tingo.models.HttpFetcher
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
        probe: (suspend () -> io.github.tancheng33.tingo.recording.CaptureProbeResult)? = null,
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
        probe = probe,
        io = dispatcher,
    )

    @Test
    fun downloadingTheRequiredModelMakesItReady() = runTest(dispatcher) {
        val bytes = "model".toByteArray()
        val file = ModelFile("asr", "asr.onnx", "https://example.test/model", bytes.size.toLong())
        val fetcher = object : HttpFetcher {
            override fun open(url: String, rangeStart: Long) = HttpFetcher.FetchedStream(
                bytes.inputStream(), resumed = false,
            )
        }
        val vm = OnboardingViewModel(
            settings = AppSettings(Store()),
            store = ModelStore(Files.createTempDirectory("onboard-download").toFile(), fetcher),
            catalog = ModelCatalog(listOf(file)),
            demo = null,
            io = dispatcher,
        )

        vm.downloadModels()
        advanceUntilIdle()

        assertTrue(vm.state.value.modelReady)
    }

    @Test
    fun failedModelDownloadIsVisibleAndCanBeRetried() = runTest(dispatcher) {
        val bytes = "model".toByteArray()
        val file = ModelFile("asr", "asr.onnx", "https://example.test/model", bytes.size.toLong())
        var online = false
        val fetcher = object : HttpFetcher {
            override fun open(url: String, rangeStart: Long): HttpFetcher.FetchedStream {
                if (!online) throw java.io.IOException("offline")
                return HttpFetcher.FetchedStream(bytes.inputStream(), resumed = false)
            }
        }
        val vm = OnboardingViewModel(
            AppSettings(Store()), ModelStore(Files.createTempDirectory("onboard-retry").toFile(), fetcher),
            ModelCatalog(listOf(file)), null, io = dispatcher,
        )

        vm.downloadModels()
        advanceUntilIdle()
        assertTrue(vm.state.value.modelDownloadFailed)
        assertFalse(vm.state.value.modelReady)

        online = true
        vm.downloadModels()
        advanceUntilIdle()
        assertFalse(vm.state.value.modelDownloadFailed)
        assertTrue(vm.state.value.modelReady)
    }

    @Test
    fun modelDownloadShowsBusyUntilItFinishes() = runTest(dispatcher) {
        val bytes = "model".toByteArray()
        val file = ModelFile("asr", "asr.onnx", "https://example.test/model", bytes.size.toLong())
        val fetcher = object : HttpFetcher {
            override fun open(url: String, rangeStart: Long) = HttpFetcher.FetchedStream(bytes.inputStream(), false)
        }
        val vm = OnboardingViewModel(
            AppSettings(Store()), ModelStore(Files.createTempDirectory("onboard-busy").toFile(), fetcher),
            ModelCatalog(listOf(file)), null, io = dispatcher,
        )

        vm.downloadModels()
        assertTrue(vm.state.value.modelDownloading)
        advanceUntilIdle()
        assertFalse(vm.state.value.modelDownloading)
        assertTrue(vm.state.value.modelReady)
    }

    @Test
    fun modelDownloadReportsOverallProgress() = runTest(dispatcher) {
        val bytes = "model".toByteArray()
        val file = ModelFile("asr", "asr.onnx", "https://example.test/model", bytes.size.toLong())
        val fetcher = object : HttpFetcher {
            override fun open(url: String, rangeStart: Long) = HttpFetcher.FetchedStream(bytes.inputStream(), false)
        }
        val vm = OnboardingViewModel(
            AppSettings(Store()), ModelStore(Files.createTempDirectory("onboard-progress").toFile(), fetcher),
            ModelCatalog(listOf(file)), null, io = dispatcher,
        )

        vm.downloadModels()
        advanceUntilIdle()
        assertEquals(1f, vm.state.value.modelDownloadProgress, 0.0001f)
    }

    @Test
    fun restartingOnboardingDoesNotStartASecondDownload() = runTest(dispatcher) {
        val bytes = "model".toByteArray()
        val file = ModelFile("asr", "asr.onnx", "https://example.test/model", bytes.size.toLong())
        var requests = 0
        val fetcher = object : HttpFetcher {
            override fun open(url: String, rangeStart: Long): HttpFetcher.FetchedStream {
                requests++
                return HttpFetcher.FetchedStream(bytes.inputStream(), false)
            }
        }
        val vm = OnboardingViewModel(
            AppSettings(Store()), ModelStore(Files.createTempDirectory("onboard-restart").toFile(), fetcher),
            ModelCatalog(listOf(file)), null, io = dispatcher,
        )

        vm.downloadModels()
        vm.restart()
        assertTrue(vm.state.value.modelDownloading)
        vm.downloadModels()
        advanceUntilIdle()
        assertEquals(1, requests)
        assertTrue(vm.state.value.modelReady)
    }

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

    @Test
    fun testCaptureShowsTheResultAndStopsSpinning() = runTest(dispatcher) {
        val vm = viewModel(
            probe = { io.github.tancheng33.tingo.recording.CaptureProbeResult(3, peak = 0.4f) },
        )

        vm.testCapture()
        assertTrue("试录期间按钮要置灰，防连点", vm.state.value.testing)
        advanceUntilIdle()

        assertFalse(vm.state.value.testing)
        assertTrue("3 秒里听到声音要如实回报", vm.state.value.probe?.heardSomething == true)
    }

    @Test
    fun silenceIsReportedInsteadOfPretendingItWorks() = runTest(dispatcher) {
        val vm = viewModel(
            probe = { io.github.tancheng33.tingo.recording.CaptureProbeResult(3, peak = 0.001f) },
        )

        vm.testCapture()
        advanceUntilIdle()

        assertFalse("底噪不能算听到", vm.state.value.probe?.heardSomething == true)
    }

    @Test
    fun secondTapWhileTestingDoesNotStartAnotherProbe() = runTest(dispatcher) {
        var runs = 0
        val vm = viewModel(
            probe = {
                runs++
                io.github.tancheng33.tingo.recording.CaptureProbeResult(3, peak = 0.2f)
            },
        )

        vm.testCapture()
        vm.testCapture()
        advanceUntilIdle()

        assertEquals("连点两次只该录一次", 1, runs)
    }

    @Test
    fun withoutAProbeTheButtonDoesNothing() = runTest(dispatcher) {
        val vm = viewModel(probe = null)

        vm.testCapture()
        advanceUntilIdle()

        assertFalse(vm.state.value.testing)
        assertNull(vm.state.value.probe)
    }

    @Test
    fun stepIsRememberedSoReturningFromSettingsContinues() = runTest(dispatcher) {
        val settings = AppSettings(Store())
        val vm = viewModel(settings)

        vm.next() // hello → model
        vm.next() // model → perm
        vm.next() // perm → llm
        assertEquals(OnboardingViewModel.Step.LLM, vm.state.value.step)
        assertEquals("离开时的那一步要落盘", 3, settings.onboardStep)

        // 模拟"去设置里填 Key"：关掉引导后重新进来
        val fresh = viewModel(settings)
        fresh.resume()
        assertEquals("该回到大模型那一步", OnboardingViewModel.Step.LLM, fresh.state.value.step)
    }

    @Test
    fun backAlsoMovesTheRememberedStep() = runTest(dispatcher) {
        val settings = AppSettings(Store())
        val vm = viewModel(settings)

        vm.next()
        vm.next()
        vm.back()

        assertEquals(1, settings.onboardStep)
        assertEquals(OnboardingViewModel.Step.MODEL, vm.state.value.step)
    }

    @Test
    fun replayStartsFromTheBeginning() = runTest(dispatcher) {
        val settings = AppSettings(Store()).apply { onboardStep = 4 }
        val vm = viewModel(settings)

        vm.restart()

        assertEquals("重玩要从头看，不能接着上次", OnboardingViewModel.Step.HELLO, vm.state.value.step)
        assertEquals(0, settings.onboardStep)
        assertFalse(settings.onboarded)
    }

    @Test
    fun anOutOfRangeStepLandsOnTheLastScreen() = runTest(dispatcher) {
        // 步数上限属于引导自己：设置里只存数字，越界在 resume() 那一侧夹住
        val settings = AppSettings(Store()).apply { onboardStep = 99 }

        val vm = viewModel(settings)
        vm.resume()

        assertEquals(OnboardingViewModel.Step.DEMO, vm.state.value.step)
    }

    @Test
    fun aNegativeStepDoesNotLandBeforeTheFirstScreen() = runTest(dispatcher) {
        val settings = AppSettings(Store()).apply { onboardStep = -5 }

        val vm = viewModel(settings)
        vm.resume()

        assertEquals(OnboardingViewModel.Step.HELLO, vm.state.value.step)
    }

    @Test
    fun goingToSettingsDoesNotCountAsFinishingTheTour() = runTest(dispatcher) {
        // 这条是那个"做了却没人能用"的坑：如果 pause 顺手把 onboarded 置真，
        // 设置页就再也不显示"继续新手引导"
        val settings = AppSettings(Store())
        val vm = viewModel(settings)
        vm.next()
        vm.next()
        vm.next()

        vm.pause()

        assertTrue("引导要收起", vm.state.value.finished)
        assertFalse("但不能算走完", settings.onboarded)
        assertEquals("步数要留住", 3, settings.onboardStep)
    }

    @Test
    fun resumingAnUnfinishedTourKeepsTheResumeEntryAvailable() = runTest(dispatcher) {
        val settings = AppSettings(Store())
        val vm = viewModel(settings)
        vm.next()
        vm.pause()

        vm.resume()

        assertFalse(vm.state.value.finished)
        assertFalse("回到引导里不该突然变成已走完", settings.onboarded)
        assertEquals(OnboardingViewModel.Step.MODEL, vm.state.value.step)
    }

    @Test
    fun skipDoesMarkTheTourFinished() = runTest(dispatcher) {
        val settings = AppSettings(Store())
        val vm = viewModel(settings)

        vm.skip()

        assertTrue(settings.onboarded)
        assertTrue(vm.state.value.finished)
    }

    @Test
    fun returningToThePermissionStepClearsTheOldProbeResult() = runTest(dispatcher) {
        val vm = viewModel(
            probe = { io.github.tancheng33.tingo.recording.CaptureProbeResult(3, peak = 0.4f) },
        )
        vm.next()
        vm.next() // 到"录音权限"
        vm.testCapture()
        advanceUntilIdle()
        assertTrue(vm.state.value.probe != null)

        vm.next() // 去"大模型"
        vm.back() // 绕回权限这一步

        assertEquals("上一次试录的结果不该显示在刚回来的这一屏", null, vm.state.value.probe)
    }
}
