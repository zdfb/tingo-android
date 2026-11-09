package io.github.tancheng33.tingo.data

import io.github.tancheng33.tingo.engine.BatchOutcome
import io.github.tancheng33.tingo.engine.MeetingSummary
import io.github.tancheng33.tingo.engine.SearchResults
import io.github.tancheng33.tingo.engine.StorageStats
import io.github.tancheng33.tingo.engine.TrashItem
import io.github.tancheng33.tingo.engine.TingoEnginePort
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 批量操作的动作名；取值与引擎边界一致，改这里就要改 Rust 侧。 */
enum class BatchAction(val wire: String) {
    ARCHIVE("archive"),
    UNARCHIVE("unarchive"),
    TAG_ADD("tag_add"),
    TAG_REMOVE("tag_remove"),
    DELETE_AUDIO("delete_audio"),
    DELETE("delete"),
    ;

    /** 加/减标签要带标签名，其它动作不需要 */
    val needsTag: Boolean get() = this == TAG_ADD || this == TAG_REMOVE
}

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
    suspend fun load(): List<MeetingCard> = withContext(io) {
        engine.scanLibrary().map { it.toCard() }
    }

    /** 归档或取消归档。 */
    suspend fun setArchived(id: String, archived: Boolean) = withContext(io) {
        engine.setArchived(id, archived)
    }

    /** 删除整场会议，返回释放的字节数。 */
    suspend fun delete(id: String): ULong = withContext(io) { engine.deleteMeeting(id) }

    /** 改标题。 */
    suspend fun rename(id: String, title: String) = withContext(io) { engine.renameMeeting(id, title) }

    /** 整体替换标签，返回归一化后的结果。 */
    suspend fun setTags(id: String, tags: List<String>): List<String> =
        withContext(io) { engine.setTags(id, tags) }

    /** 批量操作；逐 id 返回结果，界面据此提示"哪几场失败"。 */
    suspend fun batch(ids: List<String>, action: BatchAction, tag: String = "") =
        withContext(io) { engine.batch(ids, action.wire, tag) }

    /** 跨会议搜索（纪要 + 转写正文，含模糊匹配）。 */
    suspend fun search(query: String): SearchResults = withContext(io) { engine.search(query) }

    suspend fun storageStats(): StorageStats = withContext(io) { engine.storageStats() }

    /** 删除到回收站；返回释放的字节数。 */
    suspend fun deleteToTrash(id: String): ULong = withContext(io) { engine.deleteToTrash(id) }

    suspend fun restoreFromTrash(id: String) = withContext(io) { engine.restoreFromTrash(id) }

    suspend fun listTrash(): List<TrashItem> = withContext(io) { engine.listTrash() }

    suspend fun purgeTrash(id: String): ULong = withContext(io) { engine.purgeTrash(id) }
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
