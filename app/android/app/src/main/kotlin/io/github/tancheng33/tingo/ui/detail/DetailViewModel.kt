package io.github.tancheng33.tingo.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tancheng33.tingo.engine.FailureKind
import io.github.tancheng33.tingo.engine.MeetingDetail
import io.github.tancheng33.tingo.engine.MinutesDoc
import io.github.tancheng33.tingo.engine.TingoEnginePort
import io.github.tancheng33.tingo.R
import kotlinx.coroutines.CancellationException
import io.github.tancheng33.tingo.engine.MinutesVersionInfo
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * 会议详情页的状态。
 *
 * [minutesFailure] 与 [failure] 分开：纪要解析失败时转写仍然要看得到，
 * 不能因为一个章节解析不了就把整页判死。
 */
/** 正在查看的历史版本：全文 + 是否只读预览。 */
data class VersionPreview(val id: String, val text: String)

/** 猜名字的结果提示（不是错误）。 */
enum class GuessNote { NoClue }

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
    /** 猜完了但一条线索都没找到 */
    val guessNote: GuessNote? = null,
    /** 纪要历史版本（列表，含预览） */
    val history: List<MinutesVersionInfo> = emptyList(),
    /** 历史读取失败的原因；只影响历史那一栏 */
    val historyFailure: FailureKind? = null,
    /** 正在查看的历史版本全文；null 表示没在预览 */
    val preview: VersionPreview? = null,
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
    /** 录音文件；不存在时播放相关操作全部是空操作（删掉录音后置空） */
    audioFile: File? = null,
    private val playerFactory: () -> AudioPlayer = { MediaPlayerAudioPlayer() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * 界面语言（"zh"/"en"）：没改过名的说话人叫"说话人1"还是"Speaker 1"由它决定。
     *
     * 每次加载时读，与搜索一致：改完语言不必重建 ViewModel。
     */
    private val language: () -> String,
) : ViewModel() {

    /** 播放器在 IO 协程里创建、在主线程使用，跨线程读，必须可见 */
    @Volatile
    private var player: AudioPlayer? = null

    /** 录音文件；「只删录音」之后置空，播放相关操作随之变成空操作 */
    @Volatile
    private var audioFile: File? = audioFile

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    /** 读一遍纪要历史（列表只需要预览，不搬全文）。 */
    fun loadHistory() {
        viewModelScope.launch(io) {
            val versions = try {
                engine.minutesHistory(meetingId)
            } catch (e: TingoFailure) {
                // 历史读不出来只影响这一栏：不能让整页转写和播放都变成错误页
                _state.value = _state.value.copy(historyFailure = e.kind)
                return@launch
            }
            _state.value = _state.value.copy(history = versions, historyFailure = null)
        }
    }

    /** 打开某个历史版本看全文。 */
    fun openVersion(versionId: String) {
        viewModelScope.launch(io) {
            val text = try {
                engine.minutesVersion(meetingId, versionId)
            } catch (e: TingoFailure) {
                _state.value = _state.value.copy(historyFailure = e.kind)
                return@launch
            }
            _state.value = _state.value.copy(preview = VersionPreview(versionId, text))
        }
    }

    fun closePreview() {
        _state.value = _state.value.copy(preview = null)
    }

    /**
     * 用某个版本覆盖当前纪要。
     *
     * 引擎会先把"当前这版"归档，所以恢复本身也能再退回去——界面不需要额外的撤销。
     */
    fun restoreVersion(versionId: String) {
        viewModelScope.launch(io) {
            try {
                engine.restoreMinutesVersion(meetingId, versionId)
            } catch (e: TingoFailure) {
                _state.value = _state.value.copy(failure = e.kind)
                return@launch
            }
            _state.value = _state.value.copy(preview = null)
            load()
            loadHistory()
        }
    }

    fun deleteVersion(versionId: String) {
        viewModelScope.launch(io) {
            try {
                engine.deleteMinutesVersion(meetingId, versionId)
            } catch (e: TingoFailure) {
                _state.value = _state.value.copy(failure = e.kind)
                return@launch
            }
            loadHistory()
        }
    }

    /** 手工编辑纪要：与模型生成同一条保存路径，旧版自动进历史。 */
    fun saveMinutes(text: String) {
        viewModelScope.launch(io) {
            try {
                engine.saveMinutes(meetingId, text)
            } catch (e: TingoFailure) {
                _state.value = _state.value.copy(failure = e.kind)
                return@launch
            }
            load()
            loadHistory()
        }
    }

    /** 导出鱼骨图 `.excalidraw`；返回写出的路径，失败返回空。 */
    suspend fun exportExcalidraw(): List<String> = withContext(io) {
        try {
            engine.exportExcalidraw(meetingId)
        } catch (e: TingoFailure) {
            _state.value = _state.value.copy(failure = e.kind)
            emptyList()
        }
    }

    /**
     * 导出 SRT；返回文件路径，失败返回 null。
     *
     * 要读整份转写再排版写盘，不能在主线程做。
     */
    suspend fun exportSrt(language: String): String? = withContext(io) {
        try {
            engine.exportSrt(meetingId, language)
        } catch (e: TingoFailure) {
            _state.value = _state.value.copy(failure = e.kind)
            null
        }
    }

    /**
     * 只删这场会议的录音：移到回收站，转写与纪要留着。与桌面的「只删录音」同一个动作。
     *
     * @param busyIds 正在转写/写纪要的会议：删掉正在被读的录音会让那一场中途炸掉
     * @param onFreed 腾出的字节数（界面用它说"腾出 xx MB"）
     */
    fun deleteRecording(
        busyIds: List<String>,
        onFreed: (ULong) -> Unit,
        onFailed: (String) -> Unit = {},
    ) {
        viewModelScope.launch(io) {
            val freed = try {
                engine.deleteAudio(meetingId, busyIds)
            } catch (e: TingoFailure) {
                // 删录音失败不能把整页换成错误页（转写和纪要都还好好的）：交给外层弹提示，
                // 并如实带上引擎的说明（"这场会议还在处理中"比"输入不合法"有用）
                onFailed(e.detail)
                return@launch
            }
            // 先停播放再丢文件：反过来的话播放器会去读一个已经不在的文件
            runCatching {
                player?.pause()
                player?.release()
            }
            player = null
            audioFile = null
            onFreed(freed)
            load()
        }
    }

    /**
     * 给某位说话人改名。空名字表示恢复默认（"说话人N"）。
     *
     * 引擎接口是整表覆盖，所以要把其他人的名字原样带上，否则会被清空。
     */
    fun renameSpeaker(index: Int, name: String, onSaved: () -> Unit = {}) {
        val current = _state.value.speakers
        if (current.isEmpty()) return
        // 引擎是整表覆盖：没动过的说话人要发空串（引擎跳过空值）。
        // 若把默认别名"说话人2"发上去，它就被当成用户起的名字存进转写，
        // 以后重新转写时这个陈旧名字会挂到别的编号上。
        val names = current.map { speaker ->
            when {
                speaker.index == index.toUInt() -> name
                isDefaultSpeakerName(speaker.name, speaker.index.toInt()) -> ""
                else -> speaker.name
            }
        }
        viewModelScope.launch(io) {
            try {
                engine.setSpeakerNames(meetingId, names)
            } catch (e: TingoFailure) {
                _state.value = _state.value.copy(failure = e.kind)
                return@launch
            }
            load()
            // 名字写进转写之后才轮到"重新生成纪要"：两者并发会让纪要用的还是旧名字
            onSaved()
        }
    }

    /**
     * 让模型根据转写里的线索猜名字。
     *
     * 提示词与解析都在引擎里（与桌面同一份），这里只负责把两段文本送去模型、
     * 再把答案交回引擎读。
     */
    fun guessSpeakers(language: String) {
        // 连点两次会并发两次请求、后到的答案覆盖先到的
        if (_state.value.guessing) return
        val client = chat
        if (client == null) {
            _state.value = _state.value.copy(guessFailure = GuessFailure(R.string.err_llm_not_configured))
            return
        }
        _state.value = _state.value.copy(guessing = true, guessFailure = null, guessNote = null)
        viewModelScope.launch(io) {
            val failure = try {
                val prompt = engine.speakerPrompt(meetingId, language)
                // 桌面端猜名字不给 max_tokens 上限（Anthropic 给 16000）：给 1200 会在
                // 说话人多的会议里被截断，而截断在这里是错误，等于"明明能答却报错"
                val reply = client.complete(prompt.prompt, maxTokens = 8000, system = prompt.system)
                val updated = engine.parseSpeakerSuggestions(meetingId, reply)
                _state.value = _state.value.copy(
                    speakers = updated,
                    guessNote = if (updated.none { it.suggested.isNotBlank() }) {
                        GuessNote.NoClue
                    } else {
                        null
                    },
                )
                null
            } catch (e: CancellationException) {
                // 用户离开页面：不是失败，别在已销毁的界面上留一条错误
                _state.value = _state.value.copy(guessing = false)
                throw e
            } catch (e: LlmRejected) {
                GuessFailure(R.string.err_llm_rejected, e.message.orEmpty())
            } catch (e: LlmUnavailable) {
                GuessFailure(R.string.err_llm_unreachable, e.message.orEmpty())
            } catch (e: IllegalArgumentException) {
                // 提示词为空等情况：属于输入问题，不是网络
                GuessFailure(R.string.err_llm_not_configured)
            } catch (e: TingoFailure) {
                // 引擎读不了回复（MALFORMED）是"没读懂"，与"服务端拒绝"是两件事
                GuessFailure(R.string.err_llm_unreadable, e.detail)
            } catch (e: IllegalStateException) {
                GuessFailure(R.string.err_llm_unreadable, e.message.orEmpty())
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
                val lang = language()
                val detail = engine.readMeeting(meetingId, lang)
                val speakers = runCatching { engine.speakers(meetingId, lang) }.getOrDefault(emptyList())
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
                        DetailUiState(
                            loading = false,
                            detail = detail,
                            speakers = speakers,
                            minutesFailure = e.kind,
                        )
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
            // 保留历史与预览：load() 会重建整个状态，不带上就被抹成空，界面会闪一下"没有历史"
            _state.value = next.copy(
                playback = _state.value.playback,
                history = _state.value.history,
                preview = _state.value.preview,
            )
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

/**
 * 是不是"没起过名"的默认别名。
 *
 * 引擎给的默认名就是这两套（中文界面"说话人N"、英文界面"Speaker N"），
 * 判定必须和展示一致，否则改名时会把默认值当成用户输入写回去。
 */
private fun isDefaultSpeakerName(name: String, index: Int): Boolean {
    val n = index + 1
    return name == "说话人$n" || name == "Speaker $n"
}
}
