package io.github.tancheng33.tingo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tancheng33.tingo.data.LibraryBackup
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

/** 备份/恢复的结果，界面据此给一句人话。 */
sealed interface LibraryOpResult {
    data class BackedUp(val meetings: Int) : LibraryOpResult

    data class Restored(val restored: Int, val skipped: Int, val rejected: Int) : LibraryOpResult

    data object Failed : LibraryOpResult
}

data class SettingsUiState(
    val loading: Boolean = true,
    val asr: ModelGroup = ModelGroup(),
    val llm: ModelGroup = ModelGroup(),
    /** 删除失败时给出原因，而不是静默什么都不发生 */
    val deleteFailed: Boolean = false,
    /** 备份/恢复是否在跑，跑了什么结果 */
    val libraryBusy: Boolean = false,
    val libraryResult: LibraryOpResult? = null,
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
    /** 会议库根目录：备份从这里打包，恢复往这里写 */
    private val meetingsRoot: java.io.File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = _state.value.copy(loading = true, deleteFailed = false)
        viewModelScope.launch(io) { refreshNow() }
    }

    /** 删掉识别模型（四个文件一起，缺一个也用不了）。 */
    fun deleteAsr() = delete(catalog)

    /** 删掉纪要模型。 */
    fun deleteLlm() = delete(llmCatalog)

    /** 把整库写进 [open] 给出的流（SAF 的 `OutputStream` 可以直接接）。 */
    fun backupTo(open: () -> java.io.OutputStream) {
        _state.value = _state.value.copy(libraryBusy = true, libraryResult = null)
        viewModelScope.launch(io) {
            val result = runCatching { LibraryBackup.backupTo(meetingsRoot, open) }
                .fold(
                    onSuccess = { LibraryOpResult.BackedUp(it) },
                    onFailure = { LibraryOpResult.Failed },
                )
            _state.value = _state.value.copy(libraryBusy = false, libraryResult = result)
        }
    }

    /**
     * 用 [zip] 恢复会议库。
     *
     * 默认不覆盖同名会议：拿一份旧备份去还原，不该把手机上更新过的录音冲掉。
     */
    fun restoreFrom(open: () -> java.io.InputStream) {
        _state.value = _state.value.copy(libraryBusy = true, libraryResult = null)
        viewModelScope.launch(io) {
            val result = runCatching { LibraryBackup.restoreFrom(open, meetingsRoot) }
                .fold(
                    onSuccess = { LibraryOpResult.Restored(it.restored.size, it.skipped.size, it.rejected.size) },
                    onFailure = { LibraryOpResult.Failed },
                )
            _state.value = _state.value.copy(libraryBusy = false, libraryResult = result)
        }
    }

    private fun delete(target: ModelCatalog) {
        viewModelScope.launch(io) {
            val ok = runCatching { store.deleteAll(target) }.isSuccess
            // 先把占用重新算出来，再把"刚才失败过"贴上——反过来会被 refresh 覆盖掉
            refreshNow()
            if (!ok) _state.value = _state.value.copy(deleteFailed = true)
        }
    }

    /** 同步重算占用（调用方已经在 IO 线程上）。 */
    private suspend fun refreshNow() {
        val next = try {
            SettingsUiState(loading = false, asr = groupOf(catalog), llm = groupOf(llmCatalog))
        } catch (e: Exception) {
            // 统计失败（例如目录不可读）不该把界面卡在 loading
            SettingsUiState(loading = false)
        }
        _state.value = next
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
