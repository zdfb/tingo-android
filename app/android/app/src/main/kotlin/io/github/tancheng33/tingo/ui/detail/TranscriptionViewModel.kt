package io.github.tancheng33.tingo.ui.detail

import androidx.lifecycle.ViewModel
import io.github.tancheng33.tingo.R
import androidx.lifecycle.viewModelScope
import io.github.tancheng33.tingo.models.ModelCatalog
import io.github.tancheng33.tingo.models.ModelStore
import io.github.tancheng33.tingo.recording.RecordingTracks
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

    /** 模型还没下齐；[missingBytes] 用来告诉用户要下多少 */
    data class NeedModels(val missingBytes: Long) : TranscriptionUiState

    /** @param fraction 0..1 */
    data class Downloading(val fraction: Float) : TranscriptionUiState

    data class Running(val fraction: Float) : TranscriptionUiState

    data class Done(val transcript: Transcript) : TranscriptionUiState

    /** @param detail 动态部分（模型 id、异常信息）；只用于拼进已本地化的模板 */
    data class Failed(val reasonRes: Int, val detail: String? = null) : TranscriptionUiState
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
        // 崩溃留下的会议只有 mic.wav / system.wav，也算有音频
        RecordingTracks.find(meetingDir)

    /** 界面进入详情页时调一次：告诉用户当前能不能直接转写。 */
    fun prepare() {
        if (_state.value !is TranscriptionUiState.Idle) return
        val missing = catalog.files.filterNot { store.isReady(it) }
        if (missing.isEmpty()) return
        if (wavFile() == null) return
        _state.value = TranscriptionUiState.NeedModels(missingBytes = missing.sumOf { it.bytes })
    }

    fun start() {
        val wav = wavFile() ?: run {
            _state.value = TranscriptionUiState.Failed(R.string.err_no_recording)
            return
        }
        if (job?.isActive == true) return

        job = viewModelScope.launch(io) {
            try {
                val missing = catalog.files.filterNot { store.isReady(it) }
                // 规格要求：校验失败要能重下。文件坏了（引擎报错）就作废再试一次
                var retried = false
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
                                R.string.err_model_download_failed,
                                file.id,
                            )
                            return@launch
                        }
                        downloaded += file.bytes
                    }
                }
                _state.value = TranscriptionUiState.Running(0f)
                val transcript = try {
                    transcriber.transcribe(wav, meetingDir) { fraction ->
                        _state.value = TranscriptionUiState.Running(fraction)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (retried) throw e
                    // 模型文件可能损坏：作废后重下一次，仍失败才报错
                    retried = true
                    catalog.files.forEach { store.invalidate(it) }
                    catalog.files.forEach { file ->
                        val redownload = store.download(file)
                        if (redownload.isFailure) throw e
                    }
                    transcriber.transcribe(wav, meetingDir) { fraction ->
                        _state.value = TranscriptionUiState.Running(fraction)
                    }
                }
                _state.value = TranscriptionUiState.Done(transcript)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = TranscriptionUiState.Idle
                throw e
            } catch (e: Exception) {
                _state.value = TranscriptionUiState.Failed(
                    R.string.err_transcribe_failed,
                    e.message.orEmpty(),
                )
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
