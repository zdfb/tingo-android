package io.github.tancheng33.tingo.ui.record

import androidx.lifecycle.ViewModel
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

    data class Failed(val reason: String) : RecordUiState
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
) : ViewModel() {

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
            _state.value = RecordUiState.Failed(e.message ?: "麦克风不可用")
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
            _state.value = RecordUiState.Failed(e.message ?: "结束录音失败")
            return
        }
        session.finish(clock())
        currentDir = null
        _state.value = RecordUiState.Finished(dir.name, duration)
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
        ticker = viewModelScope.launch(io) {
            while (isActive) {
                val s = _state.value
                if (s is RecordUiState.Recording) {
                    _state.value = RecordUiState.Recording(session.elapsedMs(clock()), capture.level())
                }
                delay(TICK_MS)
            }
        }
    }

    private fun newMeetingId(): String {
        val now = java.time.LocalDateTime.now()
        val stamp = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm"))
        return "${stamp}_录音"
    }

    private companion object {
        const val RECORDING_FILE = "录音.wav"
        const val TICK_MS = 200L
    }
}

/** 进程被杀后恢复：把没收尾的 wav 头补好，返回时长。 */
internal fun repairRecording(file: File): Long = WavWriter.repair(file)
