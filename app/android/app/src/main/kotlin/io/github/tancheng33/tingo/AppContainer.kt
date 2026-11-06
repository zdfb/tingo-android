package io.github.tancheng33.tingo

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
import io.github.tancheng33.tingo.models.ModelStore
import io.github.tancheng33.tingo.models.UrlFetcher
import io.github.tancheng33.tingo.settings.AppSettings
import io.github.tancheng33.tingo.settings.SharedPrefsStore
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

    /** 端侧模型的清单与仓库：模型不进 APK，按清单下载到 `files/tingo/models`。 */
    val catalog: ModelCatalog = ModelCatalog.parse(
        appContext.assets.open("models.json").bufferedReader().use { it.readText() },
    )
    val models: ModelStore = ModelStore(File(dataRoot, "models"), UrlFetcher())

    /** 纪要用的语言模型：与语音模型分开列出，两套不要求同时驻留内存。 */
    val llmCatalog: ModelCatalog = ModelCatalog.parse(
        appContext.assets.open("llm.json").bufferedReader().use { it.readText() },
    )
    val llmFile: File = models.fileFor(llmCatalog.files.single())

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
