package io.github.tancheng33.tingo.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tancheng33.tingo.engine.FailureKind
import io.github.tancheng33.tingo.engine.MeetingDetail
import io.github.tancheng33.tingo.engine.MinutesDoc
import io.github.tancheng33.tingo.engine.TingoEnginePort
import io.github.tancheng33.tingo.R
import io.github.tancheng33.tingo.engine.SpeakerInfo
import io.github.tancheng33.tingo.engine.TingoFailure
import io.github.tancheng33.tingo.minutes.remote.LlmChat
import io.github.tancheng33.tingo.minutes.remote.LlmRejected
import io.github.tancheng33.tingo.minutes.remote.LlmUnavailable
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
/** 猜名字失败：一条已本地化的模板 + 动态部分（服务端原话/异常信息）。 */
data class GuessFailure(val reasonRes: Int, val detail: String = "")

data class DetailUiState(
    val loading: Boolean = true,
    val detail: MeetingDetail? = null,
    /** 说话人列表；没有转写时为空 */
    val speakers: List<SpeakerInfo> = emptyList(),
    /** AI 猜名字的进行状态 */
    val guessing: Boolean = false,
    /** 猜名字失败的原因；与纪要生成同一套文案 */
    val guessFailure: GuessFailure? = null,
    val minutes: MinutesDoc? = null,
    val minutesFailure: FailureKind? = null,
    val failure: FailureKind? = null,
    val playback: PlaybackState = PlaybackState(),
)

class DetailViewModel(
    private val engine: TingoEnginePort,
    /** 猜说话人名字要问模型服务；null 表示当前没有可用的调用通道（测试或未配置） */
    private val chat: LlmChat? = null,
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

    /**
     * 给某位说话人改名。空名字表示恢复默认（"说话人N"）。
     *
     * 引擎接口是整表覆盖，所以要把其他人的名字原样带上，否则会被清空。
     */
    fun renameSpeaker(index: Int, name: String) {
        val current = _state.value.speakers
        if (current.isEmpty()) return
        val names = current.map { if (it.index == index.toUInt()) name else it.name }
        viewModelScope.launch(io) {
            try {
                engine.setSpeakerNames(meetingId, names)
            } catch (e: TingoFailure) {
                _state.value = _state.value.copy(failure = e.kind)
                return@launch
            }
            load()
        }
    }

    /**
     * 让模型根据转写里的线索猜名字。
     *
     * 提示词与解析都在引擎里（与桌面同一份），这里只负责把两段文本送去模型、
     * 再把答案交回引擎读。
     */
    fun guessSpeakers(language: String) {
        val client = chat
        if (client == null) {
            _state.value = _state.value.copy(guessFailure = GuessFailure(R.string.err_llm_not_configured))
            return
        }
        _state.value = _state.value.copy(guessing = true, guessFailure = null)
        viewModelScope.launch(io) {
            val failure = try {
                val prompt = engine.speakerPrompt(meetingId, language)
                val reply = client.complete(prompt.prompt, maxTokens = 1200, system = prompt.system)
                val updated = engine.parseSpeakerSuggestions(meetingId, reply)
                _state.value = _state.value.copy(speakers = updated)
                null
            } catch (e: LlmRejected) {
                GuessFailure(R.string.err_llm_rejected, e.message.orEmpty())
            } catch (e: LlmUnavailable) {
                GuessFailure(R.string.err_llm_unreachable, e.message.orEmpty())
            } catch (e: IllegalArgumentException) {
                // 提示词为空等情况：属于输入问题，不是网络
                GuessFailure(R.string.err_llm_not_configured)
            } catch (e: Exception) {
                GuessFailure(R.string.err_llm_rejected, e.message.orEmpty())
            } finally {
                _state.value = _state.value.copy(guessing = false)
            }
            if (failure != null) _state.value = _state.value.copy(guessFailure = failure)
        }
    }

    /** 采纳 AI 的建议：直接把建议的名字写进去。 */
    fun applyGuess(index: Int) {
        val suggestion = _state.value.speakers.firstOrNull { it.index == index.toUInt() }?.suggested.orEmpty()
        if (suggestion.isBlank()) return
        renameSpeaker(index, suggestion)
    }

    /** 改标题；空标题由引擎归一化。失败时保留旧标题并记下原因。 */
    fun rename(title: String) {
        viewModelScope.launch(io) {
            try {
                engine.renameMeeting(meetingId, title)
            } catch (e: TingoFailure) {
                _state.value = _state.value.copy(failure = e.kind)
                return@launch
            }
            load()
        }
    }

    /** 整体替换标签（界面负责收集列表）。 */
    fun setTags(tags: List<String>) {
        viewModelScope.launch(io) {
            try {
                engine.setTags(meetingId, tags)
            } catch (e: TingoFailure) {
                _state.value = _state.value.copy(failure = e.kind)
                return@launch
            }
            load()
        }
    }

    fun load() {
        _state.value = _state.value.copy(loading = true, failure = null)
        viewModelScope.launch(io) {
            val next = try {
                val detail = engine.readMeeting(meetingId)
                val speakers = runCatching { engine.speakers(meetingId) }.getOrDefault(emptyList())
                val minutesText = detail.minutesText
                if (minutesText.isNullOrBlank()) {
                    DetailUiState(loading = false, detail = detail, speakers = speakers)
                } else {
                    try {
                        DetailUiState(
                            loading = false,
                            detail = detail,
                            speakers = speakers,
                            minutes = engine.parseMinutes(minutesText),
                        )
                    } catch (e: TingoFailure) {
                        // 纪要坏了只降级这一栏，转写照常展示
                        DetailUiState(loading = false, detail = detail, speakers = speakers, minutesFailure = e.kind)
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
