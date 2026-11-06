package io.github.tancheng33.tingo.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tancheng33.tingo.engine.FailureKind
import io.github.tancheng33.tingo.engine.MeetingDetail
import io.github.tancheng33.tingo.engine.MinutesDoc
import io.github.tancheng33.tingo.engine.TingoEnginePort
import io.github.tancheng33.tingo.engine.TingoFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 会议详情页的状态。
 *
 * [minutesFailure] 与 [failure] 分开：纪要解析失败时转写仍然要看得到，
 * 不能因为一个章节解析不了就把整页判死。
 */
data class DetailUiState(
    val loading: Boolean = true,
    val detail: MeetingDetail? = null,
    val minutes: MinutesDoc? = null,
    val minutesFailure: FailureKind? = null,
    val failure: FailureKind? = null,
)

class DetailViewModel(
    private val engine: TingoEnginePort,
    private val meetingId: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = _state.value.copy(loading = true, failure = null)
        viewModelScope.launch(io) {
            val next = try {
                val detail = engine.readMeeting(meetingId)
                val minutesText = detail.minutesText
                if (minutesText.isNullOrBlank()) {
                    DetailUiState(loading = false, detail = detail)
                } else {
                    try {
                        DetailUiState(loading = false, detail = detail, minutes = engine.parseMinutes(minutesText))
                    } catch (e: TingoFailure) {
                        // 纪要坏了只降级这一栏，转写照常展示
                        DetailUiState(loading = false, detail = detail, minutesFailure = e.kind)
                    }
                }
            } catch (e: TingoFailure) {
                DetailUiState(loading = false, failure = e.kind)
            }
            _state.value = next
        }
    }
}
