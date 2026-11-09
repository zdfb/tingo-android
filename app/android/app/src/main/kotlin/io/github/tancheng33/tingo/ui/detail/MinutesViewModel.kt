package io.github.tancheng33.tingo.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tancheng33.tingo.R
import io.github.tancheng33.tingo.minutes.MinutesService
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

    /** 还没配模型服务：不给点，先引导去设置页 */
    data object NotConfigured : MinutesUiState

    data object Generating : MinutesUiState

    data class Done(val file: File) : MinutesUiState

    /** @param detail 动态部分（校验问题、异常信息），拼进已本地化的模板 */
    data class Failed(
        val reasonRes: Int,
        val detail: String? = null,
        /** 自检不通过时保留的草稿路径 */
        val draft: File? = null,
    ) : MinutesUiState
}

/**
 * 详情页的"生成纪要"。
 *
 * 纪要现在由用户配置的模型服务生成，所以第一步是"配置齐了吗"而不是"模型下好了吗"：
 * 没配就明确告诉用户并引导去设置，而不是让他点一个只会失败的按钮。
 */
class MinutesViewModel(
    private val serviceFactory: () -> MinutesService,
    /** 模型服务是否已配置齐（地址、密钥、模型名） */
    private val isConfigured: () -> Boolean,
    private val meetingDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<MinutesUiState>(MinutesUiState.Idle)
    val state: StateFlow<MinutesUiState> = _state.asStateFlow()

    private var job: Job? = null

    /** 进入详情页时调一次：有转写但没配服务，就把入口置为不可用。 */
    fun prepare() {
        if (_state.value !is MinutesUiState.Idle) return
        viewModelScope.launch(io) {
            val hasTranscript = TranscriptWriter.read(meetingDir) != null
            if (hasTranscript && !isConfigured() && _state.value is MinutesUiState.Idle) {
                _state.value = MinutesUiState.NotConfigured
            }
        }
    }

    fun start() {
        if (job?.isActive == true) return
        if (!isConfigured()) {
            _state.value = MinutesUiState.NotConfigured
            return
        }
        val transcript = TranscriptWriter.read(meetingDir) ?: run {
            _state.value = MinutesUiState.Failed(R.string.err_no_transcript)
            return
        }
        job = viewModelScope.launch(io) {
            try {
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
                    draft = e.draft,
                )
            } catch (e: Exception) {
                // 连不上服务与"服务拒绝"在文案上要分开：用户要做的事完全不同
                val reason = if (e.message?.contains("连不上") == true) {
                    R.string.err_llm_unreachable
                } else {
                    R.string.err_minutes_failed
                }
                _state.value = MinutesUiState.Failed(reason, e.message.orEmpty())
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
