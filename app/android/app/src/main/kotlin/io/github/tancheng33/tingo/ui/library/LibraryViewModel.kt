package io.github.tancheng33.tingo.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tancheng33.tingo.data.MeetingCard
import io.github.tancheng33.tingo.data.MeetingRepository
import io.github.tancheng33.tingo.engine.FailureKind
import io.github.tancheng33.tingo.engine.TingoFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
) : ViewModel() {

    private val _state = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    /** 上一次成功的快照，用于刷新失败时保留界面内容。 */
    private var lastSnapshot: List<MeetingCard>? = null
    private var query: String = ""
    private var archivedOnly: Boolean = false

    /** 最近一次写操作的失败原因；为 null 表示没有失败。 */
    var lastError: FailureKind? = null
        private set

    init {
        refresh()
    }

    fun refresh() {
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

    fun onQueryChange(text: String) {
        query = text
        lastSnapshot?.let { _state.value = render(it) }
    }

    /** 归档或取消归档；成功后刷新列表，失败就保留原列表并记下原因。 */
    fun setArchived(id: String, archived: Boolean) {
        viewModelScope.launch(io) {
            try {
                repository.setArchived(id, archived)
            } catch (e: TingoFailure) {
                lastError = e.kind
            }
            refreshNow()
        }
    }

    /** 删除整场会议；界面负责先确认。 */
    fun delete(id: String) {
        viewModelScope.launch(io) {
            try {
                repository.delete(id)
            } catch (e: TingoFailure) {
                lastError = e.kind
            }
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
        )
    }

    private fun matchesQuery(card: MeetingCard, text: String): Boolean {
        val needle = text.trim()
        if (needle.isEmpty()) return true
        return card.title.contains(needle, ignoreCase = true) ||
            card.oneLiner.contains(needle, ignoreCase = true) ||
            card.tags.any { it.contains(needle, ignoreCase = true) }
    }
}
