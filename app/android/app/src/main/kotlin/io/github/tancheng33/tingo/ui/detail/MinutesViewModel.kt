package io.github.tancheng33.tingo.ui.detail

import androidx.lifecycle.ViewModel
import io.github.tancheng33.tingo.R
import androidx.lifecycle.viewModelScope
import io.github.tancheng33.tingo.minutes.MinutesService
import io.github.tancheng33.tingo.models.ModelStore
import io.github.tancheng33.tingo.transcription.TranscriptWriter
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 纪要生成的状态。 */
sealed interface MinutesUiState {
    data object Idle : MinutesUiState

    /** 语言模型还没下载；[missingBytes] 用来提示要下多少 */
    data class NeedModel(val missingBytes: Long) : MinutesUiState

    data object Generating : MinutesUiState

    data class Done(val file: File) : MinutesUiState

    /** @param detail 动态部分（校验问题、异常信息），拼进已本地化的模板 */
    data class Failed(val reasonRes: Int, val detail: String? = null) : MinutesUiState
}

/**
 * 详情页的"生成纪要"。
 *
 * 与转写一样分两步：先确认语言模型在，再生成。生成失败时把校验未过的原因如实说出来，
 * 而不是给一句"失败了"——用户至少知道是模型没按结构写。
 */
class MinutesViewModel(
    private val serviceFactory: () -> MinutesService,
    private val store: ModelStore,
    private val modelFile: File,
    private val meetingDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<MinutesUiState>(MinutesUiState.Idle)
    val state: StateFlow<MinutesUiState> = _state.asStateFlow()

    private var job: Job? = null

    /** 进入详情页时调一次：有转写、没模型就提示下载。 */
    fun prepare() {
        if (_state.value !is MinutesUiState.Idle) return
        if (TranscriptWriter.read(meetingDir) == null) return
        if (modelFile.isFile && modelFile.length() > 0) return
        _state.value = MinutesUiState.NeedModel(missingBytes = LLM_BYTES_HINT)
    }

    fun start() {
        if (job?.isActive == true) return
        val transcript = TranscriptWriter.read(meetingDir) ?: run {
            _state.value = MinutesUiState.Failed(R.string.err_no_transcript)
            return
        }
        job = viewModelScope.launch(io) {
            try {
                if (!modelFile.isFile || modelFile.length() == 0L) {
                    _state.value = MinutesUiState.Failed(R.string.err_llm_missing)
                    return@launch
                }
                _state.value = MinutesUiState.Generating
                val file = serviceFactory().generate(transcript, meetingDir)
                _state.value = MinutesUiState.Done(file)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = MinutesUiState.Idle
                throw e
            } catch (e: MinutesService.MinutesRejected) {
                _state.value = MinutesUiState.Failed(
                    R.string.err_minutes_rejected,
                    e.problems.firstOrNull() ?: "structure",
                )
            } catch (e: Exception) {
                _state.value = MinutesUiState.Failed(
                    R.string.err_minutes_failed,
                    e.message.orEmpty(),
                )
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private companion object {
        /** Qwen3-0.6B Q4_K_M 约 397 MB；提示语只需要量级 */
        const val LLM_BYTES_HINT = 397_000_000L
    }
}
