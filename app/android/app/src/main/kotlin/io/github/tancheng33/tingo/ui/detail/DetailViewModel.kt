package io.github.tancheng33.tingo.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tancheng33.tingo.engine.FailureKind
import io.github.tancheng33.tingo.engine.MeetingDetail
import io.github.tancheng33.tingo.engine.MinutesDoc
import io.github.tancheng33.tingo.engine.TingoEnginePort
import io.github.tancheng33.tingo.engine.TingoFailure
import io.github.tancheng33.tingo.audio.AudioPlayer
import io.github.tancheng33.tingo.audio.MediaPlayerAudioPlayer
import io.github.tancheng33.tingo.audio.WavHeader
import io.github.tancheng33.tingo.audio.PlaybackState
import java.io.File
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
    val playback: PlaybackState = PlaybackState(),
)

class DetailViewModel(
    private val engine: TingoEnginePort,
    private val meetingId: String,
    /** 录音文件；不存在时播放相关操作全部是空操作 */
    private val audioFile: File? = null,
    private val playerFactory: () -> AudioPlayer = { MediaPlayerAudioPlayer() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    /** 播放器在 IO 协程里创建、在主线程使用，跨线程读，必须可见 */
    @Volatile
    private var player: AudioPlayer? = null

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
            // 转写/纪要完成后会再次 load()：不能把正在播放的播放条抹掉，
            // 否则音频还在响、界面却没了暂停按钮。但整页读失败时要停下来，
            // 否则会出现"报错页面上悬着一个播放条"。
            if (next.failure != null) {
                // 整页读失败时不该再拉起播放器，否则会留下"报错页面上悬着一个播放条"
                releasePlayerNow()
                _state.value = next
                return@launch
            }
            _state.value = next.copy(playback = _state.value.playback)
            preparePlayback()
        }
    }

    /**
     * 有录音才准备播放器。
     *
     * 转写稿可能来自别的设备（导入了 transcript.json），没有 wav 时不该出现一个按不动的播放条。
     */
    private fun preparePlayback() {
        val file = audioFile?.takeIf { it.isFile && it.length() > WavHeader.MIN_BYTES } ?: return
        if (player != null) return
        val created = playerFactory()
        player = created
        created.prepare(file) { durationMs ->
            _state.value = _state.value.copy(
                playback = PlaybackState(available = durationMs > 0, durationMs = durationMs),
            )
        }
    }

    /** 点击某一句：跳到该句开头并开始播。 */
    fun playFrom(positionMs: Long) {
        val active = player ?: return
        active.seekTo(positionMs.coerceAtLeast(0))
        active.play()
        publishPlayback()
    }

    fun togglePlayPause() {
        val active = player ?: return
        if (active.isPlaying) active.pause() else active.play()
        publishPlayback()
    }

    /** 界面上的进度刷新：把播放器当前位置同步进状态。 */
    fun refreshPlayback() {
        if (player != null) publishPlayback()
    }

    private fun publishPlayback() {
        val active = player ?: return
        _state.value = _state.value.copy(
            playback = _state.value.playback.copy(
                playing = active.isPlaying,
                positionMs = active.positionMs,
            ),
        )
    }

    /** 单测用：ViewModel 的 onCleared 是 protected，这里给一个显式的收尾入口。 */
    internal fun releasePlayerNow() {
        player?.release()
        player = null
    }

    override fun onCleared() {
        player?.release()
        player = null
        super.onCleared()
    }
}
