package io.github.tancheng33.tingo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tancheng33.tingo.data.LibraryBackup
import io.github.tancheng33.tingo.models.ModelCatalog
import io.github.tancheng33.tingo.settings.AppSettings
import io.github.tancheng33.tingo.engine.StorageStats
import io.github.tancheng33.tingo.models.ModelStore
import io.github.tancheng33.tingo.settings.LlmConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 设置页需要引擎做、但不属于"模型文件/配置"的两件事：热词与拉取模型列表。
 *
 * 做成接口是为了让设置页能在 JVM 上测——真机上它们分别落到引擎配置和用户配置的服务上。
 */
interface HotwordsPort {
    suspend fun load(): List<String>
    suspend fun save(words: List<String>): List<String>
    /** 向表单里正在填的服务要模型列表；失败要如实返回原因 */
    suspend fun listModels(config: LlmConfig): List<String>

    /** 没有引擎时（测试/未初始化）什么都不做 */
    data object None : HotwordsPort {
        override suspend fun load(): List<String> = emptyList()
        override suspend fun save(words: List<String>): List<String> = words
        override suspend fun listModels(config: LlmConfig): List<String> = emptyList()
    }
}

/** 拉取模型列表的状态。 */
sealed interface ModelListState {
    data object Loading : ModelListState
    data class Ready(val models: List<String>) : ModelListState
    data class Failed(val reason: String) : ModelListState
}

/** 一组模型（识别用的四个文件）在磁盘上的情况。
 *
 * [readyCount] 与 [fileCount] 分开是为了区分"没下过"和"下了一半"：
 * 前者可以放心删、后者删掉就是浪费已下的流量，界面要说清楚。
 */
data class ModelGroup(
    val readyFiles: Int = 0,
    val fileCount: Int = 0,
    val bytes: Long = 0,
) {
    val isReady: Boolean get() = fileCount > 0 && readyFiles == fileCount
    val isEmpty: Boolean get() = bytes == 0L
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
    /** 纪要用的模型服务配置；默认全空 */
    val llmConfig: LlmConfig = LlmConfig(),
    /** 会议库存储统计；null 表示还没算出来 */
    val storage: StorageStats? = null,
    /** 热词（专有名词、人名、项目代号） */
    val hotwords: List<String> = emptyList(),
    /** 上一次保存热词失败了（界面据此提示，而不是照样说"已保存"） */
    val hotwordsFailed: Boolean = false,
    /** 连续多少分钟没人说话自动结束录音；0 = 不自动结束 */
    val autoStopSilenceMin: Int = io.github.tancheng33.tingo.recording.SilenceWatcher.DEFAULT_MINUTES,
    /** 拉取模型列表的结果：null 表示没拉过 */
    val models: ModelListState? = null,
    /** 删除失败时给出原因，而不是静默什么都不发生 */
    val deleteFailed: Boolean = false,
    /** 备份/恢复是否在跑，跑了什么结果 */
    val libraryBusy: Boolean = false,
    /** 内置模型解包进度 0..1；null 表示不需要解包 */
    val modelInstall: Float? = null,
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
    /** 模型服务配置的读写入口；测试里用内存实现 */
    private val settings: AppSettings,
    /** 热词读写与拉取模型列表的能力；测试里用假实现 */
    private val hotwordsPort: HotwordsPort = HotwordsPort.None,
    private val storageStats: suspend () -> StorageStats = {
        StorageStats(count = 0u, totalBytes = 0uL, audioBytes = 0uL, leftoverBytes = 0uL, largest = emptyList())
    },
    /** 会议库根目录：备份从这里打包，恢复往这里写 */
    private val meetingsRoot: java.io.File,
    /** 内置模型的解包进度；null 表示没有在解包 */
    installProgress: kotlinx.coroutines.flow.StateFlow<Float?>? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        // 首启解包 700 MB 模型要几十秒：把进度接到设置页，用户才知道不是在发呆
        installProgress?.let { flow ->
            viewModelScope.launch(io) {
                flow.collect { fraction -> _state.value = _state.value.copy(modelInstall = fraction) }
            }
        }
        refresh()
    }

    fun refresh() {
        _state.value = _state.value.copy(loading = true, deleteFailed = false)
        viewModelScope.launch(io) { refreshNow() }
    }

    /** 保存热词；返回归一化后的词表（界面据此回填）。 */
    fun saveHotwords(words: List<String>) {
        viewModelScope.launch(io) {
            val result = runCatching { hotwordsPort.save(words) }
            _state.value = _state.value.copy(
                // 写失败就保持原样：界面不该显示"已保存"却什么都没存下
                hotwords = result.getOrElse { _state.value.hotwords },
                hotwordsFailed = result.isFailure,
            )
        }
    }

    /** 保存"几分钟没说话就自动结束"；0 表示不自动结束。 */
    fun saveAutoStopSilence(minutes: Int) {
        settings.autoStopSilenceMin = minutes
        _state.value = _state.value.copy(autoStopSilenceMin = settings.autoStopSilenceMin)
    }

    /** 拉取表单里那份配置的模型列表（OpenAI 兼容是 `GET {base}/v1/models`）。 */
    fun fetchModels(config: LlmConfig) {
        _state.value = _state.value.copy(models = ModelListState.Loading)
        viewModelScope.launch(io) {
            val next = try {
                ModelListState.Ready(hotwordsPort.listModels(config))
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 协程取消不是"拉取失败"：照常往上抛，否则界面会留一条假的错误
                throw e
            } catch (e: Exception) {
                ModelListState.Failed(e.message ?: e::class.java.simpleName)
            }
            _state.value = _state.value.copy(models = next)
        }
    }

    /** 删掉识别模型（四个文件一起，缺一个也用不了）。 */
    fun deleteAsr() = delete(catalog)

    /** 保存模型服务配置；空字段保持为空由界面负责提示，这里不做静默纠正。 */
    fun saveLlmConfig(config: LlmConfig) {
        settings.llm = config
        _state.value = _state.value.copy(llmConfig = settings.llm)
    }

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
        // 只刷新"模型占用"这几个字段：整块重建会把解包进度、备份结果一起抹掉，
        // 出现"刚显示进度条又消失""备份结果闪一下没了"
        val next = try {
            val stats = runCatching { storageStats() }.getOrNull()
            val hotwords = runCatching { hotwordsPort.load() }.getOrDefault(emptyList())
            _state.value.copy(
                loading = false,
                asr = groupOf(catalog),
                llmConfig = settings.llm,
                storage = stats,
                hotwords = hotwords,
                autoStopSilenceMin = settings.autoStopSilenceMin,
            )
        } catch (e: Exception) {
            // 统计失败（例如目录不可读）不该把界面卡在 loading
            _state.value.copy(loading = false)
        }
        _state.value = next
    }

    private fun groupOf(target: ModelCatalog): ModelGroup {
        val bytes = store.usedBytes(target)
        val ready = target.files.count { store.isReady(it) }
        return ModelGroup(readyFiles = ready, fileCount = target.files.size, bytes = bytes)
    }
}
