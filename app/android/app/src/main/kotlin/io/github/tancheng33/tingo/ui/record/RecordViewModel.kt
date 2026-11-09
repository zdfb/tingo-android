package io.github.tancheng33.tingo.ui.record

import androidx.lifecycle.ViewModel
import io.github.tancheng33.tingo.R
import androidx.lifecycle.viewModelScope
import io.github.tancheng33.tingo.recording.AudioCapture
import io.github.tancheng33.tingo.recording.RecordingSession
import io.github.tancheng33.tingo.recording.RecordingTracks
import io.github.tancheng33.tingo.recording.TrackMerger
import io.github.tancheng33.tingo.recording.WavWriter
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 录音页状态。 */
sealed interface RecordUiState {
    data object Idle : RecordUiState

    /** @param level 0..1 的音量 */
    data class Recording(val elapsedMs: Long, val level: Float) : RecordUiState

    data class Paused(val elapsedMs: Long) : RecordUiState

    /** @param meetingId 目录名，用于录音结束后跳转到详情 */
    data class Finished(val meetingId: String, val durationMs: Long) : RecordUiState

    /** @param detail 动态部分（异常信息），拼进已本地化的模板 */
    data class Failed(val reasonRes: Int, val detail: String? = null) : RecordUiState
}

/**
 * 录音页状态机。
 *
 * 计时以 [RecordingSession] 为准（它知道暂停不计时），界面只负责把状态画出来；
 * 音频写入交给 [AudioCapture]——单测里换成假实现即可验证整条状态流转。
 */
class RecordViewModel(
    private val capture: AudioCapture,
    private val meetingsRoot: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * 计时协程跑在哪个作用域。默认是 ViewModel 的，测试里换成 runTest 的 backgroundScope——
     * 否则"每 200 毫秒刷新一次"的循环会让测试调度器永远不空闲。
     */
    scope: (() -> kotlinx.coroutines.CoroutineScope)? = null,
) : ViewModel() {

    /** 默认用 ViewModel 自己的作用域；测试注入 backgroundScope。 */
    private val tickerScope: () -> kotlinx.coroutines.CoroutineScope = scope ?: { viewModelScope }

    private val session = RecordingSession()
    private val _state = MutableStateFlow<RecordUiState>(RecordUiState.Idle)
    val state: StateFlow<RecordUiState> = _state.asStateFlow()

    /**
     * 系统声音那一路的状态。
     *
     * 三种状态是为了避免"刚开始录音就红字报错"：还没尝试过不能说失败，
     * 试过之后抓不到才该提示。
     */
    enum class SystemAudioStatus { NOT_ATTEMPTED, ACTIVE, MISSING }

    private val _systemAudioStatus = MutableStateFlow(SystemAudioStatus.NOT_ATTEMPTED)
    val systemAudioStatus: StateFlow<SystemAudioStatus> = _systemAudioStatus.asStateFlow()



    private var currentDir: File? = null
    private var systemAudio: io.github.tancheng33.tingo.recording.SystemAudioCapture? = null
    private var ticker: kotlinx.coroutines.Job? = null

    fun start() {
        if (_state.value is RecordUiState.Recording) return
        val dir = File(meetingsRoot, newMeetingId())
        // 原始音轨先各写各的，结束时合成 录音.wav——和桌面版的文件布局一致
        val file = File(dir, MIC_TRACK)
        try {
            dir.mkdirs()
            capture.start(file)
        } catch (e: Exception) {
            session.fail(e.message ?: "麦克风不可用")
            _state.value = RecordUiState.Failed(R.string.err_mic_unavailable, e.message)
            return
        }
        currentDir = dir
        _systemAudioStatus.value = SystemAudioStatus.NOT_ATTEMPTED
        session.start(clock())
        _state.value = RecordUiState.Recording(0, 0f)
        startTicker()
    }

    /**
     * 挂上系统声音捕获（会议软件的对方声音）。
     *
     * 必须在 [start] 之后调用：目录是 start 建的。抓不到就算了，麦克风那一路照常录。
     */
    fun attachSystemAudio(capture: io.github.tancheng33.tingo.recording.SystemAudioCapture) {
        val dir = currentDir ?: return
        runCatching { capture.start(File(dir, SYSTEM_TRACK)) }
            .onSuccess {
                systemAudio = capture
                _systemAudioStatus.value = SystemAudioStatus.ACTIVE
            }
            .onFailure {
                // 抓不到系统声音不影响录音：麦克风那一路继续，界面提示一句
                runCatching { capture.stop() }
                _systemAudioStatus.value = SystemAudioStatus.MISSING
            }
    }

    /** 服务报告"这次没抓到系统声音"：界面据此提示，麦克风那一路继续。 */
    fun systemAudioFailed() {
        _systemAudioStatus.value = SystemAudioStatus.MISSING
    }

    fun pause() {
        if (_state.value !is RecordUiState.Recording) return
        session.pause(clock())
        capture.pause()
        // 系统音轨也要停：只停麦克风会让两路在恢复后错位，合成出的录音与转写时间轴对不上
        runCatching { systemAudio?.pause() }
        _state.value = RecordUiState.Paused(session.elapsedMs(clock()))
    }

    fun resume() {
        if (_state.value !is RecordUiState.Paused) return
        session.resume(clock())
        capture.resume()
        runCatching { systemAudio?.resume() }
        _state.value = RecordUiState.Recording(session.elapsedMs(clock()), capture.level())
        startTicker()
    }

    /**
     * 结束录音。
     *
     * 只做"停止采集 + 报结果"这类快操作；混音（要整段读两路音轨）放到 IO 协程里跑，
     * 否则一小时双轨录音按下"结束"会卡住主线程。混音没完成也不影响使用：
     * [RecordingTracks.find] 会先回落到 `mic.wav`，转写时还会再补一次混音。
     */
    fun stop() {
        val dir = currentDir ?: return
        ticker?.cancel()
        ticker = null
        val duration = try {
            val micDuration = capture.stop()
            systemAudio?.let { runCatching { it.stop() } }
            systemAudio = null
            _systemAudioStatus.value = SystemAudioStatus.NOT_ATTEMPTED
            micDuration
        } catch (e: Exception) {
            session.fail(e.message ?: "结束录音失败")
            _state.value = RecordUiState.Failed(R.string.err_stop_failed, e.message)
            return
        }
        session.finish(clock())
        currentDir = null
        _state.value = RecordUiState.Finished(dir.name, duration)
        mergeInBackground(dir)
    }

    /** 后台把原始音轨合成 `录音.wav`；失败不打扰用户，转写时还有一次机会。 */
    private fun mergeInBackground(dir: File) {
        viewModelScope.launch(io) {
            runCatching { RecordingTracks.ensureMixed(dir) }
        }
    }

    /**
     * 前台服务被系统或用户结束：录音流已经断了，不能再假装在录。
     *
     * 已写入的部分保留在磁盘上，用户之后可以补转写；下次启动时由
     * [io.github.tancheng33.tingo.recording.WavWriter.repair] 补好没收尾的文件头。
     */
    fun onInterrupted() {
        val current = _state.value
        if (current !is RecordUiState.Recording && current !is RecordUiState.Paused) return
        ticker?.cancel()
        ticker = null
        runCatching { capture.stop() }
        systemAudio?.let { runCatching { it.stop() } }
        systemAudio = null
        // 中断也要把两路合起来，否则对方的声音就留在了没人看的 system.wav 里；
        // 混音放到 IO 里做，别卡住处理中断的主线程
        currentDir?.let { mergeInBackground(it) }
        currentDir = null
        _state.value = RecordUiState.Failed(R.string.err_recording_interrupted)
    }

    /** 不保存地放弃本次录音（删除空目录）。 */
    fun discard() {
        ticker?.cancel()
        ticker = null
        runCatching { capture.stop() }
        systemAudio?.let { runCatching { it.stop() } }
        systemAudio = null
        session.fail("用户取消")
        currentDir?.deleteRecursively()
        currentDir = null
        _state.value = RecordUiState.Idle
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = tickerScope().launch(io) {
            while (isActive) {
                val s = _state.value
                if (s is RecordUiState.Recording) {
                    _state.value = RecordUiState.Recording(session.elapsedMs(clock()), capture.level())
                }
                delay(TICK_MS)
            }
        }
    }

    /**
     * 会议目录名，格式与引擎的 `FOLDER` 正则严格一致：`yyyy-MM-dd_HHmm_标题`。
     *
     * 重名（同一分钟内按下两次"开始"）用 `_2`、`_3` 后缀——引擎的 `new_folder` 也是这个规则。
     * 名字里不能塞秒：引擎按 `(\d{2})(\d{2})` 取时分，多两位会让会议时间显示为空。
     */
    private fun newMeetingId(): String {
        val now = java.time.LocalDateTime.now()
        val base = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm_")) + "录音"
        if (!File(meetingsRoot, base).exists()) return base
        var index = 2
        while (File(meetingsRoot, "${base}_$index").exists()) index++
        return "${base}_$index"
    }

    companion object {
        const val RECORDING_FILE = TrackMerger.MIXED
        const val MIC_TRACK = TrackMerger.MIC_TRACK
        const val SYSTEM_TRACK = TrackMerger.SYSTEM_TRACK
        const val TICK_MS = 200L
    }
}
