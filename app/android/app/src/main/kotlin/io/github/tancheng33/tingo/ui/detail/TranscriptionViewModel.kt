package io.github.tancheng33.tingo.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tancheng33.tingo.models.ModelCatalog
import io.github.tancheng33.tingo.models.ModelStore
import io.github.tancheng33.tingo.transcription.Transcript
import io.github.tancheng33.tingo.transcription.Transcriber
import io.github.tancheng33.tingo.transcription.WavReader
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 转写任务对外的状态。 */
sealed interface TranscriptionUiState {
    data object Idle : TranscriptionUiState

    /** 模型还没下齐；[missing] 是缺的模型 id，界面据此说明要下多少 */
    data class NeedModels(val missingBytes: Long, val missing: List<String>) : TranscriptionUiState

    /** @param fraction 0..1 */
    data class Downloading(val fraction: Float) : TranscriptionUiState

    data class Running(val fraction: Float) : TranscriptionUiState

    data class Done(val transcript: Transcript) : TranscriptionUiState

    data class Failed(val reason: String) : TranscriptionUiState
}

/**
 * 会议详情页里的"转写"动作。
 *
 * 模型没下齐时先报缺多少（由界面询问是否下载），下载与识别分两个状态，
 * 这样用户知道"卡在下载"还是"卡在识别"。取消只停任务，不留半截文件。
 */
class TranscriptionViewModel(
    private val transcriber: Transcriber,
    private val store: ModelStore,
    private val catalog: ModelCatalog,
    private val meetingDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<TranscriptionUiState>(TranscriptionUiState.Idle)
    val state: StateFlow<TranscriptionUiState> = _state.asStateFlow()

    private var job: Job? = null

    /** 录音文件；m4a 之类不支持的格式返回 null（桌面录的会议手机不重转写）。 */
    private fun wavFile(): File? =
        File(meetingDir, "录音.wav").takeIf { it.isFile && it.length() > WavReader.HEADER_BYTES }

    /** 界面进入详情页时调一次：告诉用户当前能不能直接转写。 */
    fun prepare() {
        if (_state.value !is TranscriptionUiState.Idle) return
        val missing = catalog.files.filterNot { store.isReady(it) }
        if (missing.isEmpty()) return
        if (wavFile() == null) return
        _state.value = TranscriptionUiState.NeedModels(
            missingBytes = missing.sumOf { it.bytes },
            missing = missing.map { it.id },
        )
    }

    fun start() {
        val wav = wavFile() ?: run {
            _state.value = TranscriptionUiState.Failed("这场会议没有可转写的录音")
            return
        }
        if (job?.isActive == true) return

        job = viewModelScope.launch(io) {
            try {
                val missing = catalog.files.filterNot { store.isReady(it) }
                if (missing.isNotEmpty()) {
                    var downloaded = 0L
                    val total = missing.sumOf { it.bytes }.coerceAtLeast(1)
                    for (file in missing) {
                        val base = downloaded
                        val result = store.download(
                            file,
                            onProgress = { progress ->
                                val done = base + progress.downloaded
                                _state.value = TranscriptionUiState.Downloading(done.toFloat() / total)
                            },
                        )
                        if (result.isFailure) {
                            _state.value = TranscriptionUiState.Failed(
                                "模型下载失败：${file.id}",
                            )
                            return@launch
                        }
                        downloaded += file.bytes
                    }
                }
                _state.value = TranscriptionUiState.Running(0f)
                val transcript = transcriber.transcribe(wav, meetingDir) { fraction ->
                    _state.value = TranscriptionUiState.Running(fraction)
                }
                _state.value = TranscriptionUiState.Done(transcript)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = TranscriptionUiState.Idle
                throw e
            } catch (e: Exception) {
                _state.value = TranscriptionUiState.Failed(e.message ?: "转写失败")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
