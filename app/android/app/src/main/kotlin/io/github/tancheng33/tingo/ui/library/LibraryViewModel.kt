package io.github.tancheng33.tingo.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tancheng33.tingo.data.MeetingCard
import io.github.tancheng33.tingo.data.MeetingRepository
import io.github.tancheng33.tingo.ui.tasks.TaskCenter
import io.github.tancheng33.tingo.data.BatchAction
import io.github.tancheng33.tingo.engine.FailureKind
import io.github.tancheng33.tingo.engine.SearchHit
import io.github.tancheng33.tingo.engine.TrashItem
import io.github.tancheng33.tingo.engine.TingoFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 会议列表的可见状态。 */
sealed interface LibraryUiState {
    data object Loading : LibraryUiState

    data object Empty : LibraryUiState

    /**
     * @param items 过滤后的可见条目
     * @param totalCount 过滤前的条目总数，用于说明"筛选后还有多少"
     * @param unreadableCount 其中无法读取的条目数
     */
    data class Content(
        val items: List<MeetingCard>,
        val totalCount: Int,
        val unreadableCount: Int,
        val query: String = "",
        val archivedOnly: Boolean = false,
        /** 多选中的会议 id；非空时界面显示批量操作条 */
        val selection: Set<String> = emptySet(),
        /** 搜索命中：空表示"这次搜索没有命中"而不是"没在搜索" */
        val hits: List<SearchHit> = emptyList(),
        /** 引擎实际按哪些词去找（术语扩展后的结果），界面用它说明"我是这么理解的" */
        val expandedTerms: List<String> = emptyList(),
    ) : LibraryUiState

    data class Failed(val kind: FailureKind) : LibraryUiState
}

/**
 * 会议列表状态机。
 *
 * 筛选条件（搜索词、只看归档）放在这里而不是界面里：从详情页返回时，
 * 列表要停在用户离开时的样子，而 ViewModel 的生命周期比详情页长。
 */
class LibraryViewModel(
    private val repository: MeetingRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * 进行中任务登记表：会议删除/进回收站时把它的任务一起撤掉。
     *
     * 放在这里而不是外壳逐处调用——批量删除也是一条删除路径，漏掉就会出现
     * "点进去是一场不存在的会议"。
     */
    private val tasks: TaskCenter? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    /** 上一次成功的快照，用于刷新失败时保留界面内容。 */
    private var lastSnapshot: List<MeetingCard>? = null
    private var query: String = ""
    private var archivedOnly: Boolean = false

    /** 最近一次写操作的失败原因；界面据此给出提示，而不是静默失败。 */
    private val _lastError = MutableStateFlow<FailureKind?>(null)
    val lastError: StateFlow<FailureKind?> = _lastError.asStateFlow()

    /** 上一次批量操作里失败的会议数；0 表示全部成功。 */
    /**
     * 一次性失败事件：`lastError` 是状态（同样的失败再来一次不会发新值），提示需要"每次都响"，
     * 所以另开一条带自增编号的事件流。
     */
    private val _failureEvents = MutableStateFlow<LibraryFailure?>(null)
    val failureEvents: StateFlow<LibraryFailure?> = _failureEvents.asStateFlow()
    private var failureSeq = 0L

    private val _lastBatchFailures = MutableStateFlow(0)
    val lastBatchFailures: StateFlow<Int> = _lastBatchFailures.asStateFlow()

    /** 批量失败的第一条原因：只说"3 场失败"没法让人判断该做什么。 */
    private val _lastBatchReason = MutableStateFlow<String?>(null)
    val lastBatchReason: StateFlow<String?> = _lastBatchReason.asStateFlow()

    /** 回收站内容；非空且 [trashVisible] 为真时界面显示回收站视图。 */
    private val _trash = MutableStateFlow<List<TrashItem>>(emptyList())
    val trash: StateFlow<List<TrashItem>> = _trash.asStateFlow()

    /** 回收站视图是否在显示；放在这里而不是界面里，从详情页返回时不能丢。 */
    private val _trashVisible = MutableStateFlow(false)
    val trashVisible: StateFlow<Boolean> = _trashVisible.asStateFlow()
    private var selection: Set<String> = emptySet()
    private var searchJob: Job? = null
    private var hits: List<SearchHit> = emptyList()
    private var expandedTerms: List<String> = emptyList()

    init {
        refresh()
    }

    fun refresh() {
        // 用户主动刷新即视为"我知道了"，清掉上一次的操作失败提示
        _lastError.value = null
        viewModelScope.launch(io) { refreshNow() }
    }

    /** 已在 IO 协程里时直接调用，避免再起一层。 */
    private suspend fun refreshNow() {
        val next = try {
            val snapshot = repository.load()
            lastSnapshot = snapshot
            render(snapshot)
        } catch (e: TingoFailure) {
            // 刷新失败不清空已有内容：用户正在看的列表比"最新但为空"更有用
            lastSnapshot?.let { render(it) } ?: LibraryUiState.Failed(e.kind)
        }
        _state.value = next
    }

    /**
     * 搜索词变化。
     *
     * 搜索走引擎的全文检索（纪要 + 转写，含识别错字的模糊匹配），不在 Kotlin 里
     * 抄一套子串匹配：两套规则必然漂移，用户在两端搜同一句话会得到不同结果。
     */
    fun onQueryChange(text: String) {
        query = text
        lastSnapshot?.let { _state.value = render(it) }
        val needle = text.trim()
        searchJob?.cancel()
        if (needle.isEmpty()) {
            hits = emptyList()
            expandedTerms = emptyList()
            lastSnapshot?.let { _state.value = render(it) }
            return
        }
        // 每敲一个字就全库检索一次太贵（纪要 + 转写都要读盘）：等 250 ms 没有新输入再搜
        searchJob = viewModelScope.launch(io) {
            delay(SEARCH_DEBOUNCE_MS)
            val result = try {
                repository.search(needle)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TingoFailure) {
                _lastError.value = e.kind
                // 失败不能留着上一次的命中：界面会以为这些结果属于新搜索词
                hits = emptyList()
                expandedTerms = emptyList()
                lastSnapshot?.let { _state.value = render(it) }
                return@launch
            }
            // 搜索期间用户可能又改了词：只认最后一次
            if (query.trim() != needle) return@launch
            hits = result.hits
            expandedTerms = result.expanded
            lastSnapshot?.let { _state.value = render(it) }
        }
    }

    /** 归档或取消归档；成功后刷新列表，失败就保留原列表并记下原因。 */
    fun setArchived(id: String, archived: Boolean) {
        viewModelScope.launch(io) {
            try {
                repository.setArchived(id, archived)
            } catch (e: TingoFailure) {
                _lastError.value = e.kind
                _failureEvents.value = LibraryFailure(++failureSeq, e.kind)
            }
            refreshNow()
        }
    }

    /** 彻底删除整场会议（不进回收站）；界面负责先确认。 */
    fun delete(id: String) {
        viewModelScope.launch(io) {
            try {
                repository.delete(id)
                tasks?.forget(id)
            } catch (e: TingoFailure) {
                _lastError.value = e.kind
                _failureEvents.value = LibraryFailure(++failureSeq, e.kind)
            }
            refreshNow()
        }
    }

    /** 删除到回收站：可恢复，是界面上"删除"的默认动作。 */
    fun deleteToTrash(id: String) {
        viewModelScope.launch(io) {
            try {
                repository.deleteToTrash(id)
                // 进了回收站就点不开了：别让它还挂在"进行中"里
                tasks?.forget(id)
            } catch (e: TingoFailure) {
                _lastError.value = e.kind
                _failureEvents.value = LibraryFailure(++failureSeq, e.kind)
            }
            refreshNow()
        }
    }

    fun restoreFromTrash(id: String) {
        viewModelScope.launch(io) {
            try {
                repository.restoreFromTrash(id)
            } catch (e: TingoFailure) {
                _lastError.value = e.kind
                _failureEvents.value = LibraryFailure(++failureSeq, e.kind)
            }
            refreshTrash()
        }
    }

    /** 清理残留文件（合成后遗留的音轨、*.part）：与回收站无关。 */
    fun cleanupLeftovers() {
        viewModelScope.launch(io) {
            try {
                repository.cleanupLeftovers()
            } catch (e: TingoFailure) {
                _lastError.value = e.kind
                _failureEvents.value = LibraryFailure(++failureSeq, e.kind)
            }
            refreshNow()
        }
    }

    /** 清空回收站（彻底删除，不可恢复）。 */
    fun purgeAllFromTrash() {
        viewModelScope.launch(io) {
            try {
                repository.purgeTrash("")
            } catch (e: TingoFailure) {
                _lastError.value = e.kind
                _failureEvents.value = LibraryFailure(++failureSeq, e.kind)
            }
            refreshTrash()
        }
    }

    fun purgeFromTrash(id: String) {
        viewModelScope.launch(io) {
            try {
                repository.purgeTrash(id)
            } catch (e: TingoFailure) {
                _lastError.value = e.kind
                _failureEvents.value = LibraryFailure(++failureSeq, e.kind)
            }
            refreshTrash()
        }
    }

    /** 切到回收站视图。 */
    fun showTrash() {
        _trashVisible.value = true
        clearSelection()
        refreshTrash()
    }

    fun showMeetings() {
        _trashVisible.value = false
        refresh()
    }

    private fun refreshTrash() {
        viewModelScope.launch(io) {
            _trash.value = try {
                repository.listTrash()
            } catch (e: TingoFailure) {
                _lastError.value = e.kind
                emptyList()
            }
        }
    }

    /** 改名；界面负责收集输入。 */
    fun rename(id: String, title: String) {
        viewModelScope.launch(io) {
            try {
                repository.rename(id, title)
            } catch (e: TingoFailure) {
                _lastError.value = e.kind
                _failureEvents.value = LibraryFailure(++failureSeq, e.kind)
            }
            refreshNow()
        }
    }

    fun toggleSelection(id: String) {
        selection = if (id in selection) selection - id else selection + id
        lastSnapshot?.let { _state.value = render(it) }
    }

    fun clearSelection() {
        selection = emptySet()
        lastSnapshot?.let { _state.value = render(it) }
    }

    fun selectAll() {
        val all = (lastSnapshot ?: return).filter { !archivedOnly || it.archived }.map { it.id }.toSet()
        selection = all
        lastSnapshot?.let { _state.value = render(it) }
    }

    /** 对当前选择执行批量操作；部分失败只报数量，不打断其余会议。 */
    fun runBatch(action: BatchAction, tag: String = "") {
        val ids = selection.toList()
        if (ids.isEmpty()) return
        // 上一批的失败数不能带进来：用户看到的是这一批的结果
        _lastBatchFailures.value = 0
        _lastBatchReason.value = null
        viewModelScope.launch(io) {
            val failures = try {
                val outcomes = repository.batch(ids, action, tag)
                // 批量删除也是一条删除路径：漏掉这里，首页会挂着一场删掉的会议
                if (action == BatchAction.DELETE) outcomes.filter { it.ok }.forEach { tasks?.forget(it.id) }
                _lastBatchReason.value = outcomes.firstOrNull { !it.ok }?.error?.takeIf { it.isNotBlank() }
                outcomes.count { !it.ok }
            } catch (e: TingoFailure) {
                _lastError.value = e.kind
                _failureEvents.value = LibraryFailure(++failureSeq, e.kind)
                ids.size
            }
            _lastBatchFailures.value = failures
            selection = emptySet()
            refreshNow()
        }
    }

    fun onArchivedOnlyChange(enabled: Boolean) {
        archivedOnly = enabled
        lastSnapshot?.let { _state.value = render(it) }
    }

    private fun render(snapshot: List<MeetingCard>): LibraryUiState {
        val all = snapshot
        if (all.isEmpty()) return LibraryUiState.Empty

        val visible = all.filter { card ->
            (!archivedOnly || card.archived) && matchesQuery(card, query)
        }
        return LibraryUiState.Content(
            items = visible,
            totalCount = all.size,
            unreadableCount = all.count { it.unreadable },
            query = query,
            archivedOnly = archivedOnly,
            selection = selection,
            hits = hits,
            expandedTerms = expandedTerms,
        )
    }

    private fun matchesQuery(card: MeetingCard, text: String): Boolean {
        val needle = text.trim()
        if (needle.isEmpty()) return true
        return card.title.contains(needle, ignoreCase = true) ||
            card.oneLiner.contains(needle, ignoreCase = true) ||
            card.tags.any { it.contains(needle, ignoreCase = true) }
    }

    private companion object {
        /** 搜索去抖：够快看不出延迟，又不会每敲一个字就读一遍全库 */
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}

/** @param id 自增编号：同样是权限错误，第二次也要能再提示一次 */
data class LibraryFailure(val id: Long, val kind: FailureKind)
