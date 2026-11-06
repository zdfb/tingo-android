package io.github.tancheng33.tingo.ui.record

import androidx.lifecycle.ViewModel
import io.github.tancheng33.tingo.R
import androidx.lifecycle.viewModelScope
import io.github.tancheng33.tingo.recording.AudioCapture
import io.github.tancheng33.tingo.recording.RecordingSession
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

    private var currentDir: File? = null
    private var ticker: kotlinx.coroutines.Job? = null

    fun start() {
        if (_state.value is RecordUiState.Recording) return
        val dir = File(meetingsRoot, newMeetingId())
        val file = File(dir, RECORDING_FILE)
        try {
            dir.mkdirs()
            capture.start(file)
        } catch (e: Exception) {
            session.fail(e.message ?: "麦克风不可用")
            _state.value = RecordUiState.Failed(R.string.err_mic_unavailable, e.message)
            return
        }
        currentDir = dir
        session.start(clock())
        _state.value = RecordUiState.Recording(0, 0f)
        startTicker()
    }

    fun pause() {
        if (_state.value !is RecordUiState.Recording) return
        session.pause(clock())
        capture.pause()
        _state.value = RecordUiState.Paused(session.elapsedMs(clock()))
    }

    fun resume() {
        if (_state.value !is RecordUiState.Paused) return
        session.resume(clock())
        capture.resume()
        _state.value = RecordUiState.Recording(session.elapsedMs(clock()), capture.level())
        startTicker()
    }

    fun stop() {
        val dir = currentDir ?: return
        ticker?.cancel()
        ticker = null
        val duration = try {
            capture.stop()
        } catch (e: Exception) {
            session.fail(e.message ?: "结束录音失败")
            _state.value = RecordUiState.Failed(R.string.err_stop_failed, e.message)
            return
        }
        session.finish(clock())
        currentDir = null
        _state.value = RecordUiState.Finished(dir.name, duration)
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
        currentDir = null
        _state.value = RecordUiState.Failed(R.string.err_recording_interrupted)
    }

    /** 不保存地放弃本次录音（删除空目录）。 */
    fun discard() {
        ticker?.cancel()
        ticker = null
        runCatching { capture.stop() }
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
     * 会议目录名。精确到秒并做占用检查：同一分钟内按下两次"开始"不能复用目录，
     * 否则第二次会截断第一次已经写下的 wav。
     */
    private fun newMeetingId(): String {
        val now = java.time.LocalDateTime.now()
        val stamp = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))
        val base = "${stamp}_录音"
        if (!File(meetingsRoot, base).exists()) return base
        var index = 2
        while (File(meetingsRoot, "${base}-$index").exists()) index++
        return "${base}-$index"
    }

    private companion object {
        const val RECORDING_FILE = "录音.wav"
        const val TICK_MS = 200L
    }
}
