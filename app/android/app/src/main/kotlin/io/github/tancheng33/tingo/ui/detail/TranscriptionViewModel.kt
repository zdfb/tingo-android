package io.github.tancheng33.tingo.ui.detail

import androidx.lifecycle.ViewModel
import io.github.tancheng33.tingo.R
import androidx.lifecycle.viewModelScope
import io.github.tancheng33.tingo.models.ModelCatalog
import io.github.tancheng33.tingo.models.ModelStore
import io.github.tancheng33.tingo.recording.RecordingTracks
import io.github.tancheng33.tingo.transcription.Transcript
import io.github.tancheng33.tingo.ui.tasks.RunningTask
import io.github.tancheng33.tingo.ui.tasks.TaskCenter
import io.github.tancheng33.tingo.ui.tasks.TaskKind
import io.github.tancheng33.tingo.ui.tasks.meetingTitleOf
import io.github.tancheng33.tingo.transcription.Transcriber
import io.github.tancheng33.tingo.transcription.WavReader
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 转写任务对外的状态。 */
sealed interface TranscriptionUiState {
    data object Idle : TranscriptionUiState

    /** 模型还没下齐；[missingBytes] 用来告诉用户要下多少 */
    data class NeedModels(val missingBytes: Long) : TranscriptionUiState

    /** @param fraction 0..1 */
    data class Downloading(val fraction: Float) : TranscriptionUiState

    data class Running(val fraction: Float) : TranscriptionUiState

    data class Done(val transcript: Transcript) : TranscriptionUiState

    /** @param detail 动态部分（模型 id、异常信息）；只用于拼进已本地化的模板 */
    data class Failed(val reasonRes: Int, val detail: String? = null) : TranscriptionUiState
}

/**
 * 会议详情页里的"转写"动作。
 *
 * 模型没下齐时先报缺多少（由界面询问是否下载），下载与识别分两个状态，
 * 这样用户知道"卡在下载"还是"卡在识别"。取消只停任务，不留半截文件。
 */
class TranscriptionViewModel(
    private val transcriber: Transcriber,
    private val store: ModelStore,
    private val catalog: ModelCatalog,
    private val meetingDir: File,
    /** 从 APK 内置模型包解包（删过模型的用户不必联网） */
    private val ensureBundled: () -> Boolean = { false },
    /** 进行中任务登记表；null 表示没人看（测试里多数用例不关心） */
    private val tasks: TaskCenter? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<TranscriptionUiState>(TranscriptionUiState.Idle)
    val state: StateFlow<TranscriptionUiState> = _state.asStateFlow()

    private var job: Job? = null

    /** 录音文件；m4a 之类不支持的格式返回 null（桌面录的会议手机不重转写）。 */
    private fun wavFile(): File? = RecordingTracks.find(meetingDir)

    /**
     * 转写前把崩溃留下的原始音轨合成 `录音.wav`。
     *
     * 两路都在时必须混音：不然只有本机一侧的声音进转写；`system.wav` 是 48 kHz 立体声，
     * 不混音也无法直接被识别链消费。混音要整段读入，所以只在 IO 协程里、用户点了转写时做。
     */
    private fun ensureMixedForTranscription() {
        runCatching { RecordingTracks.ensureMixed(meetingDir) }
    }

    /** 界面进入详情页时调一次：告诉用户当前能不能直接转写。 */
    fun prepare() {
        if (_state.value !is TranscriptionUiState.Idle) return
        // 读 .verified 标记与 wav 头都是磁盘操作，放 IO：进详情页不该卡主线程
        viewModelScope.launch(io) {
            val missing = catalog.files.filterNot { store.isReady(it) }
            if (missing.isEmpty()) return@launch
            if (wavFile() == null) return@launch
            if (_state.value !is TranscriptionUiState.Idle) return@launch
            _state.value = TranscriptionUiState.NeedModels(missingBytes = missing.sumOf { it.bytes })
        }
    }

    fun start() {
        if (job?.isActive == true) return
        if (wavFile() == null) run {
            _state.value = TranscriptionUiState.Failed(R.string.err_no_recording)
            return
        }

        job = viewModelScope.launch(io) {
            // 崩溃留下的会议只有原始音轨：在 IO 里合成一次，再交给识别链
            ensureMixedForTranscription()
            // 模型随 APK 内置：先解包，只有包里没有时才需要下载
            runCatching { ensureBundled() }
            val wav = wavFile() ?: run {
                _state.value = TranscriptionUiState.Failed(R.string.err_no_recording)
                return@launch
            }
            try {
                val missing = catalog.files.filterNot { store.isReady(it) }
                // 规格要求：校验失败要能重下。文件坏了（引擎报错）就作废再试一次
                var retried = false
                // 先试内置包：离线设备上"删了模型只能联网重下"会直接卡死
                if (missing.isNotEmpty()) runCatching { ensureBundled() }
                val stillMissing = catalog.files.filterNot { store.isReady(it) }
                if (stillMissing.isNotEmpty()) {
                    var downloaded = 0L
                    val total = stillMissing.sumOf { it.bytes }.coerceAtLeast(1)
                    for (file in stillMissing) {
                        val base = downloaded
                        val result = store.download(
                            file,
                            onProgress = { progress ->
                                val done = base + progress.downloaded
                                _state.value = TranscriptionUiState.Downloading(done.toFloat() / total)
                            },
                        )
                        if (result.isFailure) {
                            _state.value = TranscriptionUiState.Failed(
                                R.string.err_model_download_failed,
                                file.id,
                            )
                            return@launch
                        }
                        downloaded += file.bytes
                    }
                }
                val key = RunningTask.keyOf(TaskKind.TRANSCRIBE, meetingDir.name)
                tasks?.start(key, meetingDir.name, meetingTitle(), TaskKind.TRANSCRIBE, 0f)
                _state.value = TranscriptionUiState.Running(0f)
                val transcript = try {
                    transcriber.transcribe(wav, meetingDir) { fraction ->
                        tasks?.progress(key, fraction)
                        _state.value = TranscriptionUiState.Running(fraction)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (retried) throw e
                    // 模型文件可能损坏：作废后重下一次，仍失败才报错
                    retried = true
                    catalog.files.forEach { store.invalidate(it) }
                    catalog.files.forEach { file ->
                        val redownload = store.download(file)
                        if (redownload.isFailure) throw e
                    }
                    transcriber.transcribe(wav, meetingDir) { fraction ->
                        tasks?.progress(key, fraction)
                        _state.value = TranscriptionUiState.Running(fraction)
                    }
                }
                tasks?.finish(key)
                _state.value = TranscriptionUiState.Done(transcript)
            } catch (e: kotlinx.coroutines.CancellationException) {
                tasks?.finish(RunningTask.keyOf(TaskKind.TRANSCRIBE, meetingDir.name))
                _state.value = TranscriptionUiState.Idle
                throw e
            } catch (e: Exception) {
                tasks?.finish(RunningTask.keyOf(TaskKind.TRANSCRIBE, meetingDir.name))
                _state.value = TranscriptionUiState.Failed(
                    R.string.err_transcribe_failed,
                    e.message.orEmpty(),
                )
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        // 取消也是一次结束：不撤掉，首页会挂着一件永远不会再动的活
        tasks?.finish(RunningTask.keyOf(TaskKind.TRANSCRIBE, meetingDir.name))
    }

    /** 会议标题：去掉目录名的时间前缀（与首页"进行中"、会议库显示的是同一个名字）。 */
    private fun meetingTitle(): String = meetingTitleOf(meetingDir.name)
}
