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
