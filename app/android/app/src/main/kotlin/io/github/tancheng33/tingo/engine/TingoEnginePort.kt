package io.github.tancheng33.tingo.engine

import io.github.tancheng33.tingo.engine.capabilities as nativeCapabilities
import io.github.tancheng33.tingo.engine.engineVersion as nativeEngineVersion

/**
 * 桥接层错误的统一表达。
 *
 * 类别决定界面文案，[detail] 只用于日志与排障，**不直接展示给用户**——引擎侧的
 * 英文细节（模块路径、行号、panic 文本）不该出现在界面上。
 */
enum class FailureKind {
    INVALID_INPUT,
    MISSING,
    MALFORMED,
    PERMISSION_DENIED,
    INTERNAL,
}

class TingoFailure(
    val kind: FailureKind,
    val detail: String,
    cause: Throwable? = null,
) : Exception(detail, cause)

/**
 * 界面层唯一依赖的引擎端口。
 *
 * 它把 uniFFI 自动生成的 [TingoEngine] 与生成的异常类型挡在外面，好处有两个：
 * 一是界面与 ViewModel 可以在 JVM 单测里用假实现跑（不必加载原生库），
 * 二是错误映射只写一处。
 */
interface TingoEnginePort {
    fun engineVersion(): String

    fun capabilities(): Capabilities

    /** 会议库根目录，供设置页展示与排障。 */
    fun meetingsRoot(): String

    fun scanLibrary(): List<MeetingSummary>

    fun readMeeting(id: String): MeetingDetail

    fun parseMinutes(text: String): MinutesDoc

    fun parseFishbone(text: String): Fishbone?

    /** 归档 / 取消归档；只改 meta.json 的 archived 字段。 */
    fun setArchived(id: String, archived: Boolean)

    /** 删除整场会议，返回释放的字节数。 */
    fun deleteMeeting(id: String): ULong

    /** 改标题；空标题由引擎归一化，列表会回退到纪要标题或目录名。 */
    fun renameMeeting(id: String, title: String)

    /** 整体替换标签，返回归一化后的标签（去重、去空白）。 */
    fun setTags(id: String, tags: List<String>): List<String>

    /** 批量操作，按 id 逐个返回结果；`tag` 只对 tag_add / tag_remove 有意义。 */
    fun batch(ids: List<String>, action: String, tag: String): List<BatchOutcome>

    /** 跨会议搜索（纪要正文 + 转写正文，含模糊匹配）。 */
    fun search(query: String): SearchResults

    /** 存储统计：总量、音频、可清理的残留。 */
    fun storageStats(): StorageStats

    /** 删除到回收站，返回释放的字节数。 */
    fun deleteToTrash(id: String): ULong

    fun restoreFromTrash(id: String)

    fun listTrash(): List<TrashItem>

    /** 彻底清除；`id` 为空表示清空回收站。 */
    fun purgeTrash(id: String): ULong

    /** 清理残留文件（合成后遗留的音轨、part 与 tmp 文件），返回释放的字节数。 */
    fun cleanupLeftovers(): ULong

    /**
     * 删旧会议的音频（转写与纪要保持不动）。
     *
     * @param olderThanDays 0 表示不限日期
     * @param busyIds 正在转写/写纪要的会议：一律跳过（桌面的 `meeting_busy`），
     *   否则删掉正在被读的录音会让那一场转写中途失败
     */
    fun cleanupAudio(olderThanDays: UInt, archivedOnly: Boolean, busyIds: List<String>): AudioCleanup

    /** 说话人列表（按首次发言顺序），带 AI 建议的位置。 */
    fun speakers(id: String): List<SpeakerInfo>

    /** 批量设置说话人名字；空字符串表示不改。 */
    fun setSpeakerNames(id: String, names: List<String>)

    /** 猜名字要发给模型的提示词；调用模型由页面层负责。 */
    fun speakerPrompt(id: String, language: String): SpeakerPrompt

    /** 解析模型回复，返回填好建议的说话人列表；输出不合法时抛 MALFORMED。 */
    fun parseSpeakerSuggestions(id: String, text: String): List<SpeakerInfo>

    /** 跨会议术语表（来自各场纪要的 terms 代码块）。 */
    fun glossary(): List<GlossaryTerm>

    /** 纪要历史版本，按时间从新到旧。 */
    fun minutesHistory(id: String): List<MinutesVersionInfo>

    /** 某个历史版本的全文。 */
    fun minutesVersion(id: String, versionId: String): String

    /** 用某个版本覆盖当前纪要（当前版本先进历史）。 */
    fun restoreMinutesVersion(id: String, versionId: String)

    fun deleteMinutesVersion(id: String, versionId: String)

    /** 手工编辑纪要；旧版本自动进历史。 */
    fun saveMinutes(id: String, text: String)

    /** 导出 SRT 到会议目录，返回文件绝对路径。 */
    fun exportSrt(id: String, language: String): String

    /** 把纪要里的鱼骨图导出成 .excalidraw，返回写出的文件路径（可能有多个）。 */
    fun exportExcalidraw(id: String): List<String>

    /** 当前热词（专有名词、人名、项目代号）。 */
    fun hotwords(): List<String>

    /** 保存热词；返回归一化后的结果（去空白、去重、限数）。 */
    fun setHotwords(words: List<String>): List<String>

    /** 导入一段已解码的 wav：建会议文件夹、写 `录音.wav`，返回会议 id 与目录。 */
    fun importAudio(wavPath: String, title: String, modifiedMs: Long): ImportedMeeting

    /** 把示例会议装进会议库（幂等），返回会议 id 与目录。 */
    fun installDemoMeeting(sourceDir: String, english: Boolean): ImportedMeeting

    /** 提示词里"专有名词"那段句子（不带编号）；没有热词时为空串。 */
    fun minutesGlossary(language: String): String

    companion object {
        /** 六项能力都在的实现；原生库不可用时由界面层替换为降级实现。 */
        fun native(dataRoot: String): TingoEnginePort = NativeTingoEngine(TingoEngine(dataRoot))
    }
}

/** 真实实现：只做错误翻译，不加任何业务逻辑。 */
private class NativeTingoEngine(private val engine: TingoEngine) : TingoEnginePort {
    override fun engineVersion(): String = nativeEngineVersion()

    override fun capabilities(): Capabilities = nativeCapabilities()

    override fun meetingsRoot(): String = engine.meetingsRoot()

    override fun scanLibrary(): List<MeetingSummary> = mapEngineCall { engine.scanLibrary() }

    override fun readMeeting(id: String): MeetingDetail = mapEngineCall { engine.readMeeting(id) }

    override fun parseMinutes(text: String): MinutesDoc = mapEngineCall { engine.parseMinutes(text) }

    override fun parseFishbone(text: String): Fishbone? = mapEngineCall { engine.parseFishbone(text) }

    override fun setArchived(id: String, archived: Boolean) =
        mapEngineCall { engine.setArchived(id, archived) }

    override fun deleteMeeting(id: String): ULong = mapEngineCall { engine.deleteMeeting(id) }

    override fun renameMeeting(id: String, title: String) =
        mapEngineCall { engine.renameMeeting(id, title) }

    override fun setTags(id: String, tags: List<String>): List<String> =
        mapEngineCall { engine.setTags(id, tags) }

    override fun batch(ids: List<String>, action: String, tag: String): List<BatchOutcome> =
        mapEngineCall { engine.batch(ids, action, tag) }

    override fun search(query: String): SearchResults = mapEngineCall { engine.search(query) }

    override fun storageStats(): StorageStats = mapEngineCall { engine.storageStats() }

    override fun deleteToTrash(id: String): ULong = mapEngineCall { engine.deleteToTrash(id) }

    override fun restoreFromTrash(id: String) = mapEngineCall { engine.restoreFromTrash(id) }

    override fun listTrash(): List<TrashItem> = mapEngineCall { engine.listTrash() }

    override fun purgeTrash(id: String): ULong = mapEngineCall { engine.purgeTrash(id) }

    override fun cleanupLeftovers(): ULong = mapEngineCall { engine.cleanupLeftovers() }

    override fun cleanupAudio(
        olderThanDays: UInt,
        archivedOnly: Boolean,
        busyIds: List<String>,
    ): AudioCleanup = mapEngineCall { engine.cleanupAudio(olderThanDays, archivedOnly, busyIds) }

    override fun speakers(id: String): List<SpeakerInfo> = mapEngineCall { engine.speakers(id) }

    override fun setSpeakerNames(id: String, names: List<String>) =
        mapEngineCall { engine.setSpeakerNames(id, names) }

    override fun speakerPrompt(id: String, language: String): SpeakerPrompt =
        mapEngineCall { engine.speakerPrompt(id, language) }

    override fun parseSpeakerSuggestions(id: String, text: String): List<SpeakerInfo> =
        mapEngineCall { engine.parseSpeakerSuggestions(id, text) }

    override fun glossary(): List<GlossaryTerm> = mapEngineCall { engine.glossary() }

    override fun minutesHistory(id: String): List<MinutesVersionInfo> =
        mapEngineCall { engine.minutesHistory(id) }

    override fun minutesVersion(id: String, versionId: String): String =
        mapEngineCall { engine.minutesVersion(id, versionId) }

    override fun restoreMinutesVersion(id: String, versionId: String) =
        mapEngineCall { engine.restoreMinutesVersion(id, versionId) }

    override fun deleteMinutesVersion(id: String, versionId: String) =
        mapEngineCall { engine.deleteMinutesVersion(id, versionId) }

    override fun saveMinutes(id: String, text: String) = mapEngineCall { engine.saveMinutes(id, text) }

    override fun exportSrt(id: String, language: String): String =
        mapEngineCall { engine.exportSrt(id, language) }

    override fun exportExcalidraw(id: String): List<String> =
        mapEngineCall { engine.exportExcalidraw(id) }

    override fun hotwords(): List<String> = mapEngineCall { engine.hotwords() }

    override fun setHotwords(words: List<String>): List<String> = mapEngineCall { engine.setHotwords(words) }

    override fun importAudio(wavPath: String, title: String, modifiedMs: Long): ImportedMeeting =
        mapEngineCall { engine.importAudio(wavPath, title, modifiedMs) }

    override fun installDemoMeeting(sourceDir: String, english: Boolean): ImportedMeeting =
        mapEngineCall { engine.installDemoMeeting(sourceDir, english) }

    override fun minutesGlossary(language: String): String =
        mapEngineCall { engine.minutesGlossary(language) }
}

/** 把生成的异常翻译成 [TingoFailure]；未识别的异常一律算内部错误。 */
internal fun <T> mapEngineCall(block: () -> T): T =
    try {
        block()
    } catch (e: EngineException.InvalidInput) {
        throw TingoFailure(FailureKind.INVALID_INPUT, e.message.orEmpty(), e)
    } catch (e: EngineException.Missing) {
        throw TingoFailure(FailureKind.MISSING, e.message.orEmpty(), e)
    } catch (e: EngineException.Malformed) {
        throw TingoFailure(FailureKind.MALFORMED, e.message.orEmpty(), e)
    } catch (e: EngineException.PermissionDenied) {
        throw TingoFailure(FailureKind.PERMISSION_DENIED, e.message.orEmpty(), e)
    } catch (e: EngineException.Internal) {
        throw TingoFailure(FailureKind.INTERNAL, e.message.orEmpty(), e)
    }
