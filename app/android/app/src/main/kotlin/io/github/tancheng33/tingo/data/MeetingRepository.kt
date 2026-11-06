package io.github.tancheng33.tingo.data

import io.github.tancheng33.tingo.engine.MeetingSummary
import io.github.tancheng33.tingo.engine.TingoEnginePort
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 会议库只读访问。
 *
 * 扫描与排序由引擎完成（它才知道目录命名规则），这里只做两件事：
 * 在工作线程上执行，以及把引擎模型投影成界面模型。
 */
class MeetingRepository(
    private val engine: TingoEnginePort,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * 读取会议库快照。
     *
     * 单目录损坏不算失败——引擎已把它标成 `unreadable` 放进列表；
     * 只有整体扫描失败（根目录不可读等）才会抛 [io.github.tancheng33.tingo.engine.TingoFailure]。
     */
    suspend fun load(): LibrarySnapshot = withContext(io) {
        LibrarySnapshot(engine.scanLibrary().map { it.toCard() })
    }
}

internal fun MeetingSummary.toCard(): MeetingCard = MeetingCard(
    id = id,
    title = title,
    date = date,
    time = time,
    duration = duration,
    oneLiner = oneLiner,
    tags = tags,
    archived = archived,
    hasAudio = hasAudio,
    hasMinutes = hasMinutes,
    unreadable = unreadable,
)
