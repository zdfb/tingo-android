package io.github.tancheng33.tingo.ui.detail

import androidx.lifecycle.ViewModel
import io.github.tancheng33.tingo.R
import androidx.lifecycle.viewModelScope
import io.github.tancheng33.tingo.minutes.MinutesService
import io.github.tancheng33.tingo.models.ModelCatalog
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

    /** 正在下载语言模型；[fraction] 为 0..1 */
    data class Downloading(val fraction: Float) : MinutesUiState

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
 * 与转写一样分两步：先确认语言模型在，再生成。生成失败时把校验未过的原因如实说出来，
 * 而不是给一句"失败了"——用户至少知道是模型没按结构写。
 */
class MinutesViewModel(
    private val serviceFactory: () -> MinutesService,
    private val modelFile: File,
    private val meetingDir: File,
    /** 语言模型的下载器；缺失时由本 ViewModel 自己按清单下载 */
    private val store: ModelStore? = null,
    private val catalog: ModelCatalog? = null,
    /** 从 APK 内置模型包解包（删过模型的用户不必联网） */
    private val ensureBundled: () -> Boolean = { false },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<MinutesUiState>(MinutesUiState.Idle)
    val state: StateFlow<MinutesUiState> = _state.asStateFlow()

    private var job: Job? = null

    /** 进入详情页时调一次：有转写、没模型就提示下载。读文件放到 IO。 */
    fun prepare() {
        if (_state.value !is MinutesUiState.Idle) return
        viewModelScope.launch(io) {
            val hasTranscript = TranscriptWriter.read(meetingDir) != null
            val modelReady = isModelReady()
            if (hasTranscript && !modelReady && _state.value is MinutesUiState.Idle) {
                _state.value = MinutesUiState.NeedModel(missingBytes = LLM_BYTES_HINT)
            }
        }
    }

    fun start() {
        if (job?.isActive == true) return
        val transcript = TranscriptWriter.read(meetingDir) ?: run {
            _state.value = MinutesUiState.Failed(R.string.err_no_transcript)
            return
        }
        job = viewModelScope.launch(io) {
            try {
                // 语言模型也走和语音模型一样的下载路径：新装的应用没有它，
                // 用户点一下就该自己下回来，而不是永远停在"还没下载好"
                if (!downloadModelIfNeeded()) {
                    // 下载失败已经写进状态；清单里找不到对应文件时补一句"模型缺失"，
                    // 否则点了"生成纪要"什么都不会发生
                    if (_state.value !is MinutesUiState.Failed) {
                        _state.value = MinutesUiState.Failed(R.string.err_llm_missing)
                    }
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
                    draft = e.draft,
                )
            } catch (e: OutOfMemoryError) {
                // 0.6B 模型在低内存设备上可能装不下；规格要求"只保留转写稿、其它功能不受影响"
                _state.value = MinutesUiState.Failed(R.string.err_llm_unsupported)
            } catch (e: Exception) {
                _state.value = MinutesUiState.Failed(
                    R.string.err_minutes_failed,
                    e.message.orEmpty(),
                )
            }
        }
    }

    /**
     * 缺语言模型时按清单下载；返回是否已经可用。
     *
     * 失败时把"下载失败"如实报出来——以前这里只报"模型还没下载好"，
     * 而应用里根本没有触发下载的入口，等于这条功能在新机器上永远用不了。
     */
    private suspend fun downloadModelIfNeeded(): Boolean {
        if (isModelReady()) return true
        // 先试内置包：模型随 APK 分发，正常路径完全不联网
        if (runCatching { ensureBundled() }.getOrDefault(false) && isModelReady()) return true
        val file = catalog?.files?.firstOrNull { store?.fileFor(it) == modelFile } ?: return isModelReady()
        if (store == null) return isModelReady()
        if (store.isReady(file)) return true
        _state.value = MinutesUiState.Downloading(0f)
        val result = store.download(
            file,
            onProgress = { progress ->
                val fraction = (progress.downloaded.toFloat() / file.bytes.coerceAtLeast(1)).coerceIn(0f, 1f)
                _state.value = MinutesUiState.Downloading(fraction)
            },
        )
        if (result.isFailure) {
            _state.value = MinutesUiState.Failed(R.string.err_model_download_failed, result.exceptionOrNull()?.message)
            return false
        }
        return true
    }

    /**
     * 模型是否就绪。
     *
     * 有清单时按清单判断（体积 + 校验指纹）：只看"文件非空"会让一个被截断的 GGUF
     * 通过检查，然后死在推理引擎里，用户看到的是"生成失败"而不是"模型不完整"。
     */
    private fun isModelReady(): Boolean {
        val file = catalog?.files?.firstOrNull { store?.fileFor(it) == modelFile }
        if (file != null && store != null) return store.isReady(file)
        return modelFile.isFile && modelFile.length() > 0
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
