package io.github.tancheng33.tingo

import io.github.tancheng33.tingo.ui.components.ToastCenter
import io.github.tancheng33.tingo.ui.tasks.TaskCenter

import android.content.Context
import io.github.tancheng33.tingo.engine.AbiCheck
import io.github.tancheng33.tingo.engine.Capabilities
import io.github.tancheng33.tingo.engine.FailureKind
import io.github.tancheng33.tingo.engine.Fishbone
import io.github.tancheng33.tingo.engine.MeetingDetail
import io.github.tancheng33.tingo.engine.MeetingSummary
import io.github.tancheng33.tingo.engine.MinutesDoc
import io.github.tancheng33.tingo.engine.TingoEnginePort
import io.github.tancheng33.tingo.engine.TingoFailure
import io.github.tancheng33.tingo.models.ModelCatalog
import io.github.tancheng33.tingo.recording.InterruptedRecordings
import io.github.tancheng33.tingo.models.ModelStore
import io.github.tancheng33.tingo.models.UrlFetcher
import io.github.tancheng33.tingo.settings.AppLanguage
import io.github.tancheng33.tingo.settings.AppSettings
import io.github.tancheng33.tingo.settings.SharedPrefsStore
import io.github.tancheng33.tingo.models.AssetModelBundle
import io.github.tancheng33.tingo.models.ModelBundle
import io.github.tancheng33.tingo.models.ModelInstaller
import java.io.File

/**
 * 手动依赖容器：只有一个进程级实例，由 [TingoApplication] 持有。
 *
 * 不引 Hilt：本应用只有个位数的依赖，注解处理器带来的构建成本与不可见性都不划算。
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settings = AppSettings(SharedPrefsStore(appContext))

    /** 引擎要求的沙箱根目录；会议、配置、缓存在它下面按约定分三个子目录。 */
    val dataRoot: File = File(
        appContext.getExternalFilesDir(null) ?: appContext.filesDir,
        "tingo",
    )

    /**
     * 进行中任务登记表。
     *
     * 首页那块"进行中"与各页的 ViewModel 共用同一份：没有它，用户点开转写再切回来
     * 就看不到任何进展——而转写正是要跑几分钟、最需要"它在动"的那类活。
     */
    val tasks: TaskCenter = TaskCenter()

    /** 全局提示（桌面的 toast）：外壳画一次，各页只负责 `show()` */
    val toasts: ToastCenter = ToastCenter()

    /**
     * ABI 不受支持时引擎不可用；此时界面仍然可以打开，只是没有数据。
     * 这一步必须在加载原生库**之前**判断，否则拿到的是难以理解的链接错误。
     */
    val engine: TingoEnginePort =
        if (AbiCheck.isSupported()) {
            try {
                TingoEnginePort.native(dataRoot.absolutePath)
            } catch (e: Throwable) {
                UnavailableEngine(e)
            }
        } else {
            UnavailableEngine(IllegalStateException("unsupported abi"))
        }

    /** 语音模型的清单与仓库：模型随 APK 内置，解包到 `files/tingo/models`。 */
    val catalog: ModelCatalog = ModelCatalog.parse(
        appContext.assets.open("models.json").bufferedReader().use { it.readText() },
    )
    val models: ModelStore = ModelStore(File(dataRoot, "models"), UrlFetcher())

    /** 内置模型包（`assets/models/`）。 */
    private val bundle: ModelBundle = AssetModelBundle(appContext.assets)

    private val installMutex = Any()

    /** 内置模型的解包进度：0..1；null 表示不需要解包。 */
    private val _installProgress = kotlinx.coroutines.flow.MutableStateFlow<Float?>(null)
    val installProgress: kotlinx.coroutines.flow.StateFlow<Float?> = _installProgress

    /**
     * 把内置模型解包到模型目录。
     *
     * 幂等：已经解包过就直接返回，所以可以在启动与转写两处都放心调用。
     * 安装包缺模型（例如开发时没跑 `fetch-models-offline.sh`）时返回 false，
     * 此时界面仍走原来的下载路径。
     */
    fun installBundledModels(): Boolean {
        synchronized(installMutex) {
            if (catalog.files.all { models.isReady(it) }) {
                return true
            }
            _installProgress.value = 0f
            val asrOk = ModelInstaller.install(models, catalog, bundle) { fraction ->
                _installProgress.value = fraction
            }
            _installProgress.value = null
            return asrOk
        }
    }

    /**
     * 引擎认的语言标识：`zh` / `en`。
     *
     * 引擎只认这两个值，而设置里存的是 `zh-CN` / `en`；界面与导出都要用同一个判断，
     * 否则会出现"界面英文、字幕中文"。
     */
    fun uiLanguage(): String = if (settings.language == AppLanguage.EN) "en" else "zh"

    /** 启动时在后台解包内置模型；不阻塞首帧。 */
    fun installBundledModelsOnStart() {
        kotlin.concurrent.thread(name = "tingo-models", isDaemon = true) {
            runCatching { installBundledModels() }
        }
    }

    /**
     * 启动时修复没来得及收尾的录音（进程被杀 / 服务被系统结束）。
     *
     * 放到后台线程做：会议多的时候要逐个读文件头，不该卡启动。
     */
    fun repairInterruptedRecordingsOnStart() {
        kotlin.concurrent.thread(name = "tingo-repair", isDaemon = true) {
            runCatching { InterruptedRecordings.repairUnder(File(dataRoot, "meetings")) }
        }
    }

    /** 单个会议的目录；id 是会议库根目录下的单层目录名。 */
    fun meetingDir(id: String): File = File(File(dataRoot, "meetings"), id)

    /**
     * 首次启动时建好会议库目录并写入探针文件。
     *
     * 写探针而不是只 `mkdirs()`：目录存在但不可写（外部存储未挂载、被系统清理）时，
     * 只检查存在性会给出错误的"一切正常"。
     */
    fun ensureDataRoot(): Boolean {
        val probe = File(dataRoot, ".tingo-probe")
        return try {
            val dir = File(dataRoot, "meetings")
            dir.mkdirs()
            probe.writeText(System.currentTimeMillis().toString())
            probe.delete()
            dir.isDirectory
        } catch (_: Exception) {
            false
        }
    }
}

/** 引擎不可用时的替身：所有需要引擎的调用都失败，界面据此提示而不是崩溃。 */
private class UnavailableEngine(private val cause: Throwable) : TingoEnginePort {
    private fun fail(): Nothing =
        throw TingoFailure(FailureKind.INTERNAL, "engine unavailable: ${cause.message}", cause)

    override fun engineVersion(): String = "unavailable"

    override fun setArchived(id: String, archived: Boolean): Nothing = fail()

    override fun deleteMeeting(id: String): Nothing = fail()

    override fun renameMeeting(id: String, title: String): Nothing = fail()

    override fun setTags(id: String, tags: List<String>): Nothing = fail()

    override fun batch(ids: List<String>, action: String, tag: String): Nothing = fail()

    override fun search(query: String): Nothing = fail()

    override fun storageStats(): Nothing = fail()

    override fun deleteToTrash(id: String): Nothing = fail()

    override fun restoreFromTrash(id: String): Nothing = fail()

    override fun listTrash(): Nothing = fail()

    override fun purgeTrash(id: String): Nothing = fail()

    override fun cleanupLeftovers(): Nothing = fail()

    override fun speakers(id: String): Nothing = fail()

    override fun setSpeakerNames(id: String, names: List<String>): Nothing = fail()

    override fun speakerPrompt(id: String, language: String): Nothing = fail()

    override fun parseSpeakerSuggestions(id: String, text: String): Nothing = fail()

    override fun glossary(): Nothing = fail()

    override fun minutesHistory(id: String): Nothing = fail()

    override fun minutesVersion(id: String, versionId: String): Nothing = fail()

    override fun restoreMinutesVersion(id: String, versionId: String): Nothing = fail()

    override fun deleteMinutesVersion(id: String, versionId: String): Nothing = fail()

    override fun saveMinutes(id: String, text: String): Nothing = fail()

    override fun exportSrt(id: String, language: String): Nothing = fail()

    override fun exportExcalidraw(id: String): Nothing = fail()

    override fun hotwords(): Nothing = fail()

    override fun setHotwords(words: List<String>): Nothing = fail()

    override fun importAudio(wavPath: String, title: String, modifiedMs: Long): Nothing = fail()

    override fun installDemoMeeting(sourceDir: String, english: Boolean): Nothing = fail()

    override fun minutesGlossary(language: String): String = ""

    override fun capabilities(): Capabilities =
        Capabilities(
            meetingStorage = false,
            transcriptParse = false,
            minutesParse = false,
            fishboneParse = false,
            speakerNaming = false,
            termsExtract = false,
        )

    override fun meetingsRoot(): String = ""

    override fun scanLibrary(): List<MeetingSummary> = fail()

    override fun readMeeting(id: String): MeetingDetail = fail()

    override fun parseMinutes(text: String): MinutesDoc = fail()

    override fun parseFishbone(text: String): Fishbone? = fail()
}
