package io.github.tancheng33.tingo.ui.onboard

import androidx.lifecycle.ViewModel
import io.github.tancheng33.tingo.importing.DemoInstaller
import io.github.tancheng33.tingo.models.ModelCatalog
import io.github.tancheng33.tingo.models.ModelStore
import io.github.tancheng33.tingo.recording.CaptureProbeResult
import io.github.tancheng33.tingo.settings.AppSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope

/**
 * 新手引导的五步，与桌面一致：认识 Tingo → 识别模型 → 录音权限 → 大模型 → 示例会议。
 *
 * 每一步都可以跳过；走完（或点"跳过"）就记下来，下次不再自动弹出，
 * 设置页里可以重新打开。
 */
class OnboardingViewModel(
    private val settings: AppSettings,
    private val store: ModelStore,
    private val catalog: ModelCatalog,
    private val demo: DemoInstaller?,
    /** 试录探针：由外壳注入（要麦克风与缓存目录；ViewModel 不该自己拿 Context） */
    private val probe: (suspend () -> CaptureProbeResult)? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    enum class Step { HELLO, MODEL, PERMISSION, LLM, DEMO }

    data class UiState(
        val step: Step = Step.HELLO,
        /** 识别模型是否已就绪（Android 的模型是随包内置的，通常第一步就绿） */
        val modelReady: Boolean = false,
        val installingDemo: Boolean = false,
        val demoFailed: Boolean = false,
        /** 示例会议装好后交给外面跳转 */
        val demoMeetingId: String? = null,
        /** 引导走完了（或跳过）：外面据此把引导收起来 */
        val finished: Boolean = false,
        /** 正在试录（3 秒）：按钮置灰，避免连点 */
        val testing: Boolean = false,
        /** 上一次试录的结果；null 表示还没试过 */
        val probe: CaptureProbeResult? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val steps: List<Step> = Step.entries

    /** 第一次打开时才自动进引导：已经走过、或已经有会议，都不拦人。 */
    fun shouldOnboard(hasMeetings: Boolean): Boolean = !settings.onboarded && !hasMeetings

    /**
     * 引导第三步的"试录 3 秒"：真的打开麦克风录一段，回答"有没有声音"。
     *
     * 只测麦克风。系统内录在 Android 上每次都要过一遍系统"录屏"授权，
     * 引导里弹这个授权框会让人莫名其妙——录音页会在真正需要时才问。
     */
    fun testCapture() {
        val probe = probe ?: return
        if (_state.value.testing) return
        _state.value = _state.value.copy(testing = true, probe = null)
        // MicRecorder.start/stop 是阻塞的 IO：放在默认线程会卡住主线程的动画
        viewModelScope.launch(io) {
            val result = probe()
            _state.value = _state.value.copy(testing = false, probe = result)
        }
    }

    fun refreshModel() {
        _state.value = _state.value.copy(modelReady = catalog.files.all { store.isReady(it) })
    }

    fun next() {
        val index = steps.indexOf(_state.value.step)
        if (index < steps.lastIndex) {
            _state.value = _state.value.copy(step = steps[index + 1])
            if (_state.value.step == Step.MODEL) refreshModel()
        } else {
            skip()
        }
    }

    fun back() {
        val index = steps.indexOf(_state.value.step)
        if (index > 0) _state.value = _state.value.copy(step = steps[index - 1])
    }

    /** 走完最后一步：装示例会议并交回会议 id。 */
    fun installDemo(english: Boolean) {
        val installee = demo ?: return
        if (_state.value.installingDemo) return
        _state.value = _state.value.copy(installingDemo = true, demoFailed = false)
        viewModelScope.launch(io) {
            val id = installee.install(english)
            if (id != null) settings.onboarded = true
            _state.value = _state.value.copy(
                installingDemo = false,
                demoFailed = id == null,
                demoMeetingId = id,
            )
        }
    }

    /** 跳过或走完：记下来，以后不再自动弹。 */
    fun skip() {
        settings.onboarded = true
        _state.value = _state.value.copy(finished = true)
    }

    /** 界面拿走跳转信号后清掉，避免重复跳。 */
    fun consumeDemo() {
        _state.value = _state.value.copy(demoMeetingId = null)
    }

    /** 设置页里"重新看一遍引导"用。 */
    fun restart() {
        settings.onboarded = false
        _state.value = UiState()
        refreshModel()
    }
}
