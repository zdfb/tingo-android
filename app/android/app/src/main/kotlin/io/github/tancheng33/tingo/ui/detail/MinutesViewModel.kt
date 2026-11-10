package io.github.tancheng33.tingo.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tancheng33.tingo.R
import io.github.tancheng33.tingo.minutes.MinutesService
import io.github.tancheng33.tingo.ui.tasks.RunningTask
import io.github.tancheng33.tingo.ui.tasks.TaskCenter
import io.github.tancheng33.tingo.ui.tasks.TaskKind
import io.github.tancheng33.tingo.minutes.remote.LlmRejected
import io.github.tancheng33.tingo.minutes.remote.LlmUnavailable
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
    /**
     * 最终纪要的保存动作。null 表示直接写文件；Android 注入引擎的保存路径，
     * 让"重新生成"也把上一版归档进历史。
     */
    private val saveMinutes: ((String) -> Unit)? = null,
    /** 模型服务是否已配置齐（地址、密钥、模型名） */
    private val isConfigured: () -> Boolean,
    private val meetingDir: File,
    /** 进行中任务登记表；null 表示没人看（测试里多数用例不关心） */
    private val tasks: TaskCenter? = null,
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
                // 纪要只有"在写"，没有可量化进度：首页显示成一条没有百分比的活
                val key = RunningTask.keyOf(TaskKind.MINUTES, meetingDir.name)
                tasks?.start(key, meetingDir.name, meetingTitle(), TaskKind.MINUTES)
                _state.value = MinutesUiState.Generating
                val service = serviceFactory()
                val file = service.generate(
                    transcript,
                    meetingDir,
                    saveFinal = saveMinutes ?: { text -> service.write(meetingDir, text); Unit },
                )
                tasks?.finish(key)
                _state.value = MinutesUiState.Done(file)
            } catch (e: kotlinx.coroutines.CancellationException) {
                tasks?.finish(RunningTask.keyOf(TaskKind.MINUTES, meetingDir.name))
                _state.value = MinutesUiState.Idle
                throw e
            } catch (e: MinutesService.MinutesRejected) {
                tasks?.finish(RunningTask.keyOf(TaskKind.MINUTES, meetingDir.name))
                _state.value = MinutesUiState.Failed(
                    R.string.err_minutes_rejected,
                    e.problems.firstOrNull() ?: "structure",
                    draft = e.draft,
                )
            } catch (e: LlmUnavailable) {
                tasks?.finish(RunningTask.keyOf(TaskKind.MINUTES, meetingDir.name))
                _state.value = MinutesUiState.Failed(R.string.err_llm_unreachable, e.message.orEmpty())
            } catch (e: LlmRejected) {
                tasks?.finish(RunningTask.keyOf(TaskKind.MINUTES, meetingDir.name))
                _state.value = MinutesUiState.Failed(R.string.err_llm_rejected, e.message.orEmpty())
            } catch (e: Exception) {
                tasks?.finish(RunningTask.keyOf(TaskKind.MINUTES, meetingDir.name))
                _state.value = MinutesUiState.Failed(R.string.err_minutes_failed, e.message.orEmpty())
            }
        }
    }

    /** 取消：立刻回到"可再次点击"的状态，别让界面在取消后还挂着"生成中"。 */
    fun cancel() {
        job?.cancel()
        job = null
        // 取消也是一次结束：不撤掉，首页会挂着一件永远不会再动的活
        tasks?.finish(RunningTask.keyOf(TaskKind.MINUTES, meetingDir.name))
        _state.value = MinutesUiState.Idle
    }

    /** 会议标题：取目录名去掉时间前缀（与首页"进行中"显示的一致）。 */
    private fun meetingTitle(): String = meetingDir.name.substringAfterLast('_', meetingDir.name)
}
