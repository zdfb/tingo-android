package io.github.tancheng33.tingo.data

import io.github.tancheng33.tingo.engine.MeetingSummary
import io.github.tancheng33.tingo.engine.SearchResults
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

}

/**
 * 会议库的列表类操作（扫描、搜索、归档/删除/回收站、批量、清理残留）。
 *
 * 扫描与排序由引擎完成（它才知道目录命名规则），这里只做两件事：
 * 在工作线程上执行，以及把引擎模型投影成界面模型。
 *
 * 只放"会议库这一屏真正用到的东西"：详情页与纪要页的调用直接用引擎端口，
 * 在这里再包一层只会让人以为"访问会议库都得绕道仓库"。
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

    /** 批量操作；逐 id 返回结果，界面据此提示"哪几场失败"。 */
    suspend fun batch(ids: List<String>, action: BatchAction, tag: String = "") =
        withContext(io) { engine.batch(ids, action.wire, tag) }

    /** 跨会议搜索（纪要 + 转写正文，含模糊匹配）。 */
    /** 搜索；`language` 决定没改名的说话人怎么显示（说话人1 / Speaker 1） */
    suspend fun search(query: String, language: String = "zh"): SearchResults =
        withContext(io) { engine.search(query, language) }

    /** 删除到回收站；返回释放的字节数。 */
    suspend fun deleteToTrash(id: String): ULong = withContext(io) { engine.deleteToTrash(id) }

    suspend fun restoreFromTrash(id: String) = withContext(io) { engine.restoreFromTrash(id) }

    suspend fun listTrash(): List<TrashItem> = withContext(io) { engine.listTrash() }

    suspend fun purgeTrash(id: String): ULong = withContext(io) { engine.purgeTrash(id) }

    /** 清理残留文件（合成后遗留的音轨、part 与 tmp）；返回释放的字节数。 */
    suspend fun cleanupLeftovers(): ULong = withContext(io) { engine.cleanupLeftovers() }
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
