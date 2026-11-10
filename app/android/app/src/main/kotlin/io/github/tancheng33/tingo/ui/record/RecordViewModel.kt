package io.github.tancheng33.tingo.ui.record

import androidx.lifecycle.ViewModel
import io.github.tancheng33.tingo.R
import androidx.lifecycle.viewModelScope
import io.github.tancheng33.tingo.importing.AudioDecoder
import io.github.tancheng33.tingo.importing.AudioImport
import io.github.tancheng33.tingo.recording.AudioCapture
import io.github.tancheng33.tingo.recording.LiveTranscriber
import io.github.tancheng33.tingo.recording.MicRecorder
import io.github.tancheng33.tingo.recording.RecordingSession
import io.github.tancheng33.tingo.recording.RecordingTracks
import io.github.tancheng33.tingo.recording.SilenceWatcher
import io.github.tancheng33.tingo.recording.WavTrim
import io.github.tancheng33.tingo.recording.RecordingKeepAlive
import io.github.tancheng33.tingo.recording.SystemAudioTrack
import io.github.tancheng33.tingo.recording.TrackMerger
import io.github.tancheng33.tingo.ui.tasks.RunningTask
import io.github.tancheng33.tingo.ui.tasks.TaskCenter
import io.github.tancheng33.tingo.ui.tasks.TaskKind
import io.github.tancheng33.tingo.recording.WavWriter
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 录音页状态。 */
sealed interface RecordUiState {
    data object Idle : RecordUiState

    /** @param level 0..1 的音量 */
    data class Recording(val elapsedMs: Long, val level: Float) : RecordUiState

    data class Paused(val elapsedMs: Long) : RecordUiState

    /** @param meetingId 目录名，用于录音结束后跳转到详情 */
    data class Finished(val meetingId: String, val durationMs: Long) : RecordUiState

    /** @param detail 动态部分（异常信息），拼进已本地化的模板 */
    data class Failed(val reasonRes: Int, val detail: String? = null) : RecordUiState
}

/**
 * 录音页状态机。
 *
 * 计时以 [RecordingSession] 为准（它知道暂停不计时），界面只负责把状态画出来；
 * 音频写入交给 [AudioCapture]——单测里换成假实现即可验证整条状态流转。
 */
class RecordViewModel(
    private val capture: AudioCapture,
    private val meetingsRoot: File,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 收尾时把前台服务一起停掉；由界面注入（ViewModel 不该自己拿 Context） */
    private val stopService: () -> Unit = {},
    /**
     * 静音自动结束：连续多少分钟没人说话就停；0 表示不自动停。
     *
     * 从设置读（与桌面同一条配置），由外壳注入；默认关闭的写法会让用户以为"不会自动停"，
     * 所以缺省给桌面的默认值 10 分钟。
     */
    private val autoStopSilenceMin: () -> Int = { SilenceWatcher.DEFAULT_MINUTES },
    /** 导入已有录音；null 表示这个构建不支持（测试里多数用例不关心） */
    private val importing: AudioImport? = null,
    /** 进行中任务登记表：导入要跑几分钟，离开录制页也得看得见 */
    private val tasks: TaskCenter? = null,
    /**
     * 边录边转的工厂：每句识别结果回调一次。
     *
     * null 表示这个构建不做实时文字（测试、模型没就绪）。传工厂而不是实例，
     * 是因为每次录音都要新建一份——上一次的 VAD 状态不能带进下一场会。
     */
    private val liveTranscriber: ((onSentence: (String) -> Unit) -> LiveTranscriber)? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * 计时协程跑在哪个作用域。默认是 ViewModel 的，测试里换成 runTest 的 backgroundScope——
     * 否则"每 200 毫秒刷新一次"的循环会让测试调度器永远不空闲。
     */
    scope: (() -> kotlinx.coroutines.CoroutineScope)? = null,
) : ViewModel() {

    /** 默认用 ViewModel 自己的作用域；测试注入 backgroundScope。 */
    private val tickerScope: () -> kotlinx.coroutines.CoroutineScope = scope ?: { viewModelScope }

    private val session = RecordingSession()
    private val _state = MutableStateFlow<RecordUiState>(RecordUiState.Idle)
    val state: StateFlow<RecordUiState> = _state.asStateFlow()

    /**
     * 系统声音那一路的状态。
     *
     * 三种状态是为了避免"刚开始录音就红字报错"：还没尝试过不能说失败，
     * 试过之后抓不到才该提示。
     */
    enum class SystemAudioStatus { NOT_ATTEMPTED, ACTIVE, MISSING }

    private val _systemAudioStatus = MutableStateFlow(SystemAudioStatus.NOT_ATTEMPTED)
    val systemAudioStatus: StateFlow<SystemAudioStatus> = _systemAudioStatus.asStateFlow()

    /**
     * 边录边转的实时句子（只有麦克风那一路）。
     *
     * 只留最近几条：这是"看得见还在干活"的反馈，不是最终转写；详细内容录完写进
     * `transcript.json`。留着全部会让界面越来越卡，也会让人误以为可以直接编辑。
     */
    private val _liveLines = MutableStateFlow<List<String>>(emptyList())
    val liveLines: StateFlow<List<String>> = _liveLines.asStateFlow()

    @Volatile
    private var live: LiveTranscriber? = null

    /**
     * 实时转写的"场次号"。
     *
     * 上一场的推理线程可能还在收尾（最长几秒），它的回调不该把句子灌进这一场。
     * 用自增场次号判断，比拿对象自己引用自己清楚得多。
     */
    @Volatile
    private var liveSession = 0



    /**
     * 导入任务的状态。
     *
     * 单独一条流而不是并进 [RecordUiState]：导入与录音是两条互不干扰的路径，
     * 混进同一个状态机只会让"正在录音时能不能导入"这种问题变成一堆分支。
     */
    data class ImportState(
        val progress: Float? = null,
        /** 失败原因：界面据此说"格式不支持"还是"解码失败"，不能一律"失败了" */
        val failure: ImportFailure? = null,
        val meetingId: String? = null,
    )

    /** 导入失败的两类原因（用户要做的事不同：换文件 vs 查文件/重试）。 */
    enum class ImportFailure { UNSUPPORTED, UNREADABLE }

    private val _import = MutableStateFlow(ImportState())
    val import: StateFlow<ImportState> = _import.asStateFlow()

    /**
     * 导入一段录音：解码 → 落成会议 → 转写。
     *
     * @param modifiedMs 文件修改时间（当作会议时间）；0 用现在
     */
    fun importAudio(source: File, title: String, modifiedMs: Long) {
        val importer = importing ?: return
        if (_import.value.progress != null) return // 已经在导入：不叠第二个
        // 标记要在启动协程之前就打上：连点两次时协程还没跑起来，检查会漏
        _import.value = ImportState(progress = 0f)
        // 一次导入一个 key：会议目录建好之前还没有 id，只能用序号占位
        val taskKey = RunningTask.pendingKey(TaskKind.IMPORT, System.nanoTime().toString())
        // 与落库时的会议名同一条规则（去扩展名），首页那一行才不会带着 .m4a
        val shownTitle = title.ifBlank { AudioDecoder.titleOf(source.name) }
        tasks?.start(taskKey, meetingId = "", title = shownTitle, kind = TaskKind.IMPORT, fraction = 0f)
        // 解码占进度条前 15%（与 AudioImport 同一条分配），此后由转写回调继续推
        viewModelScope.launch(io) {
            try {
                val id = importer.run(
                    source = source,
                    title = title,
                    modifiedMs = modifiedMs,
                    onCreated = { created ->
                        // 目录建好后补上真实 id（原地替换，别让这一行跳到列表末尾）；
                        // 进度已知，不要闪一下"没进度"
                        tasks?.attach(taskKey, created, shownTitle, fraction = AudioImport.DECODE_SHARE)
                    },
                    onProgress = { fraction ->
                        _import.value = ImportState(progress = fraction)
                        tasks?.progress(taskKey, fraction)
                    },
                )
                _import.value = ImportState(progress = null, meetingId = id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _import.value = ImportState()
                throw e
            } catch (e: Exception) {
                _import.value = ImportState(failure = ImportFailure.UNREADABLE)
            } finally {
                // 成功、失败、取消都一样：从"进行中"撤掉
                tasks?.finish(taskKey)
            }
        }
    }

    /** 选到的文件不是支持的音视频：与"解码失败"分开报，用户该做的事不同。 */
    fun importRejected() {
        if (_import.value.progress != null) return
        _import.value = ImportState(failure = ImportFailure.UNSUPPORTED)
    }

    /** 界面拿走"导入完成"的信号后清掉，避免重复跳转。 */
    fun consumeImported() {
        _import.value = _import.value.copy(meetingId = null)
    }

    /** 静音判断：整段录音只用一个实例，开始新的一场时清零 */
    private val silence = SilenceWatcher()

    /**
     * 上一次录音是被"没人说话"自动结束的：分钟数。界面据此提示一句，
     * 用户看到"10 分钟没人说话，已自动结束录音"才不会以为是应用崩了。
     */
    private val _autoStoppedAfter = MutableStateFlow<Int?>(null)
    val autoStoppedAfter: StateFlow<Int?> = _autoStoppedAfter.asStateFlow()

    /** 提示看过就清掉，避免切页回来又弹一次。 */
    fun consumeAutoStopNotice() {
        _autoStoppedAfter.value = null
    }

    private var currentDir: File? = null
    private var systemAudio: SystemAudioTrack? = null
    private var ticker: kotlinx.coroutines.Job? = null

    fun start() {
        if (_state.value is RecordUiState.Recording) return
        // 录音是"写进去就收不回来"的操作：空间不够就明说，别让用户录到一半被 ENOSPC 打断。
        // 16 kHz 单声道约 115 MB/小时，双轨（48 kHz 立体声）约 690 MB/小时，留 500 MB 兜底。
        val free = runCatching { meetingsRoot.usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (free in 1 until MIN_FREE_BYTES) {
            _state.value = RecordUiState.Failed(R.string.err_low_storage, null)
            return
        }
        val dir = File(meetingsRoot, newMeetingId())
        // 原始音轨先各写各的，结束时合成 录音.wav——和桌面版的文件布局一致
        val file = File(dir, MIC_TRACK)
        try {
            dir.mkdirs()
            capture.start(file)
        } catch (e: Exception) {
            session.fail(e.message ?: "麦克风不可用")
            _state.value = RecordUiState.Failed(R.string.err_mic_unavailable, e.message)
            return
        }
        startLiveTranscription()
        currentDir = dir
        silence.reset()
        silence.silenceMinutes = autoStopSilenceMin()
        _autoStoppedAfter.value = null
        _systemAudioStatus.value = SystemAudioStatus.NOT_ATTEMPTED
        session.start(clock())
        _state.value = RecordUiState.Recording(0, 0f)
        startTicker()
    }

    /** 开一场录音就换一份实时转写：VAD 与识别流的状态不能跨场复用。 */
    private fun startLiveTranscription() {
        val factory = liveTranscriber ?: return
        val mic = capture as? MicRecorder ?: return // 只有真实麦克风有样本回调
        _liveLines.value = emptyList()
        val session = ++liveSession
        val created = runCatching {
            factory { sentence ->
                // 上一场的推理线程可能还在收尾：它的句子不该灌进这一场
                if (liveSession != session) return@factory
                // 回调在推理线程上：`update` 是原子的读改写；手写
                // "value = value + s" 会让并发回调互相覆盖（丢句、顺序乱）
                _liveLines.update { lines -> (lines + sentence).takeLast(LIVE_LINES_SHOWN) }
            }
        }.getOrNull() ?: return
        // 先挂上引用再启动：反过来的话第一句可能因为"还没挂上"被丢掉
        live = created
        created.start()
        mic.onSamples = { samples -> created.offer(samples) }
    }

    /**
     * 停止录音时关掉实时转写：识别线程不该比录音活得久。
     *
     * `close()` 会 join 推理线程（最多 3 秒）并释放四个原生模型，放到 IO 上跑，
     * 否则收尾这几秒会卡住界面。
     */
    private fun stopLiveTranscription(joinNow: Boolean = false) {
        (capture as? MicRecorder)?.onSamples = null
        val transcriber = live ?: return
        if (joinNow) {
            // onCleared 之后 viewModelScope 已经取消，launch 出去的协程不会执行：
            // 这里必须同步收尾（join 上限 3 秒，且这是最后一次机会）
            liveSession++
            live = null
            transcriber.close()
            return
        }
        // 收尾放到 IO 上：join 最多 3 秒，不该卡界面。场次号在 close 之后才推进，
        // 让推理线程把缓冲里最后那几句发完
        viewModelScope.launch(io) {
            transcriber.close()
            if (live === transcriber) live = null
        }
    }

    /**
     * 挂上系统声音捕获（会议软件的对方声音）。
     *
     * 必须在 [start] 之后调用：目录是 start 建的。抓不到就算了，麦克风那一路照常录。
     */
    fun attachSystemAudio(capture: SystemAudioTrack) {
        val dir = currentDir ?: run {
            // 录音已经停了（或根本没开始）：这条抓到的投影没人用，必须自己停掉，
            // 否则系统的"正在录屏"指示会一直亮着
            runCatching { capture.stop() }
            return
        }
        runCatching { capture.start(File(dir, SYSTEM_TRACK)) }
            .onSuccess {
                systemAudio = capture
                _systemAudioStatus.value = SystemAudioStatus.ACTIVE
            }
            .onFailure {
                // 抓不到系统声音不影响录音：麦克风那一路继续，界面提示一句
                runCatching { capture.stop() }
                _systemAudioStatus.value = SystemAudioStatus.MISSING
            }
    }

    /** 服务报告"这次没抓到系统声音"：界面据此提示，麦克风那一路继续。 */
    fun systemAudioFailed() {
        _systemAudioStatus.value = SystemAudioStatus.MISSING
    }

    fun pause() {
        if (_state.value !is RecordUiState.Recording) return
        session.pause(clock())
        capture.pause()
        // 系统音轨也要停：只停麦克风会让两路在恢复后错位，合成出的录音与转写时间轴对不上
        runCatching { systemAudio?.pause() }
        _state.value = RecordUiState.Paused(session.elapsedMs(clock()))
    }

    fun resume() {
        if (_state.value !is RecordUiState.Paused) return
        session.resume(clock())
        capture.resume()
        runCatching { systemAudio?.resume() }
        _state.value = RecordUiState.Recording(session.elapsedMs(clock()), capture.level())
        startTicker()
    }

    /**
     * 结束录音。
     *
     * 只做"停止采集 + 报结果"这类快操作；混音（要整段读两路音轨）放到 IO 协程里跑，
     * 否则一小时双轨录音按下"结束"会卡住主线程。混音没完成也不影响使用：
     * [RecordingTracks.find] 会先回落到 `mic.wav`，转写时还会再补一次混音。
     */
    /**
     * @param keepMs 只保留到这一刻（静音自动结束时用）：之后的内容都是一片安静，
     *   留着只会让文件变大、转写变慢。null 表示整段都留。
     */
    fun stop(keepMs: Long? = null) {
        val dir = currentDir ?: return
        ticker?.cancel()
        ticker = null
        val duration = try {
            // 先让采集线程收尾（它会再交最后一批样本），再关实时转写：
            // 顺序反了最后几百毫秒的话就永远进不了实时文字
            val micDuration = capture.stop()
            stopLiveTranscription()
            systemAudio?.let { runCatching { it.stop() } }
            systemAudio = null
            _systemAudioStatus.value = SystemAudioStatus.NOT_ATTEMPTED
            // 采集线程中途死掉（磁盘满、写入被关闭）：文件已写下的部分还在，
            // 但必须告诉用户"这次录音提前中断了"，不能让计时器继续装作一切正常
            capture.lastFailure()?.let { failure ->
                session.fail(failure.message ?: "录音中断")
                _state.value = RecordUiState.Failed(R.string.err_stop_failed, failure.message)
                mergeInBackground(dir)
                currentDir = null
                return
            }
            micDuration
        } catch (e: Exception) {
            session.fail(e.message ?: "结束录音失败")
            _state.value = RecordUiState.Failed(R.string.err_stop_failed, e.message)
            return
        }
        session.finish(clock())
        currentDir = null
        _state.value = RecordUiState.Finished(dir.name, duration)
        // 自动结束：先截掉静音的尾巴，再混音。两件事必须在同一个协程里按顺序做，
        // 否则混音可能读到"正在被截短"的文件，混出来的长度就说不准了
        mergeInBackground(dir, keepMs)
    }

    /**
     * 后台把原始音轨合成 `录音.wav`；失败不打扰用户，转写时还有一次机会。
     *
     * @param keepMs 自动结束时只保留到这一刻：先截两路原始音轨，再混音
     */
    private fun mergeInBackground(dir: File, keepMs: Long? = null) {
        viewModelScope.launch(io) {
            if (keepMs != null) {
                listOf(MIC_TRACK, SYSTEM_TRACK).forEach { name ->
                    runCatching { WavTrim.trimTo(File(dir, name), keepMs) }
                }
            }
            runCatching { RecordingTracks.ensureMixed(dir) }
        }
    }

    /**
     * 前台服务被系统或用户结束：录音流已经断了，不能再假装在录。
     *
     * 已写入的部分保留在磁盘上，用户之后可以补转写；下次启动时由
     * [io.github.tancheng33.tingo.recording.WavWriter.repair] 补好没收尾的文件头。
     */
    fun onInterrupted() {
        val current = _state.value
        if (current !is RecordUiState.Recording && current !is RecordUiState.Paused) return
        ticker?.cancel()
        ticker = null
        runCatching { capture.stop() }
        // 中断同样是"这一场到此为止"：实时转写的线程与模型要一起收掉
        stopLiveTranscription(joinNow = true)
        systemAudio?.let { runCatching { it.stop() } }
        systemAudio = null
        // 中断也要把两路合起来，否则对方的声音就留在了没人看的 system.wav 里；
        // 混音放到 IO 里做，别卡住处理中断的主线程
        currentDir?.let { mergeInBackground(it) }
        currentDir = null
        _state.value = RecordUiState.Failed(R.string.err_recording_interrupted)
    }

    /** 不保存地放弃本次录音（删除空目录）。 */
    /**
     * 界面彻底销毁时收尾。
     *
     * 录音的采集器与写文件都在 ViewModel 里：它被清理时不关掉，麦克风与前台服务就成了孤儿，
     * 用户既看不到界面也停不下来。切到别的页不会触发这一步（ViewModel 跟着 Activity 活），
     * 但退出应用、或系统回收界面时会。
     */
    override fun onCleared() {
        // 实时转写持有四个原生模型与一条线程：离开录音页不关就是永久泄漏。
        // 这里必须同步收尾——onCleared 时 viewModelScope 已经取消，异步的 close 不会执行
        stopLiveTranscription(joinNow = true)
        if (_state.value is RecordUiState.Recording || _state.value is RecordUiState.Paused) {
            runCatching { capture.stop() }
            currentDir?.let { mergeInBackground(it) }
            _state.value = RecordUiState.Idle
            RecordingKeepAlive.publish(null)
            runCatching { stopService() }
        }
        super.onCleared()
    }

    fun discard() {
        ticker?.cancel()
        ticker = null
        runCatching { capture.stop() }
        stopLiveTranscription(joinNow = true)
        systemAudio?.let { runCatching { it.stop() } }
        systemAudio = null
        session.fail("用户取消")
        currentDir?.deleteRecursively()
        currentDir = null
        _state.value = RecordUiState.Idle
    }

    /**
     * 系统内录的"静音检查"。
     *
     * 会议软件可以通过 `allowAudioPlaybackCapture=false` 禁止被抓，此时系统不报错，
     * 只是给一段静音。所以录到 [SILENCE_CHECK_MS] 后看一眼峰值：还是 0 就判定没抓到，
     * 界面明确告诉用户"这次只有本机的声音"，而不是把静音混进录音让人觉得内录成功了。
     */
    private fun checkSystemAudioSilence() {
        val capture = systemAudio ?: return
        val elapsed = session.elapsedMs(clock())
        if (elapsed < SILENCE_CHECK_MS) return
        val hasSound = capture.peakLevel() > SILENCE_THRESHOLD
        when (_systemAudioStatus.value) {
            // 会议刚开始可能前几秒没人说话：判错了还要能翻回来，否则一直显示"没抓到"
            SystemAudioStatus.ACTIVE -> if (!hasSound) _systemAudioStatus.value = SystemAudioStatus.MISSING
            SystemAudioStatus.MISSING -> if (hasSound) _systemAudioStatus.value = SystemAudioStatus.ACTIVE
            else -> Unit
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = tickerScope().launch(io) {
            while (isActive) {
                val s = _state.value
                if (s is RecordUiState.Recording) {
                    // 两路取大的：只有对方说话（自己不吭声）时也不能算静音
                    val level = maxOf(capture.level(), runCatching { systemAudio?.peakLevel() ?: 0f }.getOrDefault(0f))
                    val elapsed = session.elapsedMs(clock())
                    _state.value = RecordUiState.Recording(elapsed, level)
                    checkSystemAudioSilence()
                    silence.observe(level, elapsed)?.let { autoStop ->
                        // 与点"结束录音"同一条路径：先停采集，再后台混音
                        _autoStoppedAfter.value = autoStop.minutes
                        stop(keepMs = autoStop.keepMs)
                        return@launch
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    /**
     * 会议目录名，格式与引擎的 `FOLDER` 正则严格一致：`yyyy-MM-dd_HHmm_标题`。
     *
     * 重名（同一分钟内按下两次"开始"）用 `_2`、`_3` 后缀——引擎的 `new_folder` 也是这个规则。
     * 名字里不能塞秒：引擎按 `(\d{2})(\d{2})` 取时分，多两位会让会议时间显示为空。
     */
    private fun newMeetingId(): String {
        val now = java.time.LocalDateTime.now()
        val base = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm_")) + "录音"
        if (!File(meetingsRoot, base).exists()) return base
        var index = 2
        while (File(meetingsRoot, "${base}_$index").exists()) index++
        return "${base}_$index"
    }

    companion object {
        const val MIC_TRACK = TrackMerger.MIC_TRACK
        const val SYSTEM_TRACK = TrackMerger.SYSTEM_TRACK
        const val TICK_MS = 200L
        /** 开始录音前至少要有这么多空间（约 20 分钟双轨录音）。 */
        const val MIN_FREE_BYTES = 500L * 1024 * 1024
        /** 界面上留几条实时句子 */
        const val LIVE_LINES_SHOWN = 3
        /** 录这么久之后检查系统音轨有没有内容。 */
        const val SILENCE_CHECK_MS = 5_000L
        /** 低于这个振幅就当没抓到（16 bit 满量程 1.0）。 */
        const val SILENCE_THRESHOLD = 0.001f
    }
}
