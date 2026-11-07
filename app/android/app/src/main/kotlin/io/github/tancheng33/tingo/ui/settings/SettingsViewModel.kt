package io.github.tancheng33.tingo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tancheng33.tingo.models.ModelCatalog
import io.github.tancheng33.tingo.models.ModelStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 一组模型（识别用的四个文件、或纪要用的一个 GGUF）在磁盘上的情况。
 *
 * [readyCount] 与 [fileCount] 分开是为了区分"没下过"和"下了一半"：
 * 前者可以放心删、后者删掉就是浪费已下的流量，界面要说清楚。
 */
data class ModelGroup(
    val readyFiles: Int = 0,
    val fileCount: Int = 0,
    val bytes: Long = 0,
    val totalBytes: Long = 0,
) {
    val isReady: Boolean get() = fileCount > 0 && readyFiles == fileCount
    val isEmpty: Boolean get() = bytes == 0L
    val partial: Boolean get() = !isReady && !isEmpty
}

data class SettingsUiState(
    val loading: Boolean = true,
    val asr: ModelGroup = ModelGroup(),
    val llm: ModelGroup = ModelGroup(),
    /** 删除失败时给出原因，而不是静默什么都不发生 */
    val deleteFailed: Boolean = false,
)

/**
 * 设置页里"模型占用"的那一块。
 *
 * 模型占 700 多兆，用户需要一个能看到、能清掉的地方——只在下载对话框里提一句不够。
 */
class SettingsViewModel(
    private val store: ModelStore,
    private val catalog: ModelCatalog,
    private val llmCatalog: ModelCatalog,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = _state.value.copy(loading = true, deleteFailed = false)
        viewModelScope.launch(io) {
            val groups = try {
                SettingsUiState(loading = false, asr = groupOf(catalog), llm = groupOf(llmCatalog))
            } catch (e: Exception) {
                SettingsUiState(loading = false)
            }
            _state.value = groups
        }
    }

    /** 删掉识别模型（四个文件一起，缺一个也用不了）。 */
    fun deleteAsr() = delete(catalog)

    /** 删掉纪要模型。 */
    fun deleteLlm() = delete(llmCatalog)

    private fun delete(target: ModelCatalog) {
        viewModelScope.launch(io) {
            val ok = runCatching { store.deleteAll(target) }.isSuccess
            _state.value = _state.value.copy(deleteFailed = !ok)
            refresh()
        }
    }

    private fun groupOf(target: ModelCatalog): ModelGroup {
        val bytes = store.usedBytes(target)
        val ready = target.files.count { store.isReady(it) }
        return ModelGroup(
            readyFiles = ready,
            fileCount = target.files.size,
            bytes = bytes,
            totalBytes = target.files.sumOf { it.bytes },
        )
    }
}
