package io.github.tancheng33.tingo.testing

import io.github.tancheng33.tingo.engine.Capabilities
import io.github.tancheng33.tingo.engine.Fishbone
import io.github.tancheng33.tingo.engine.MeetingDetail
import io.github.tancheng33.tingo.engine.MeetingSummary
import io.github.tancheng33.tingo.engine.MinutesDoc
import io.github.tancheng33.tingo.engine.TingoEnginePort
import io.github.tancheng33.tingo.engine.BatchOutcome
import io.github.tancheng33.tingo.engine.SearchResults
import io.github.tancheng33.tingo.engine.StorageStats
import io.github.tancheng33.tingo.engine.TingoFailure
import io.github.tancheng33.tingo.engine.TrashItem

/** 会议列表项构造器：字段太多，测试里用命名参数写一次省得每处重复。 */
fun summary(
    id: String,
    title: String = id,
    date: String = "2026-01-01",
    time: String = "10:00",
    oneLiner: String = "",
    tags: List<String> = emptyList(),
    archived: Boolean = false,
    hasAudio: Boolean = true,
    hasMinutes: Boolean = false,
    hasTranscript: Boolean = true,
    unreadable: Boolean = false,
    duration: String = "",
): MeetingSummary = MeetingSummary(
    id = id,
    title = title,
    date = date,
    time = time,
    oneLiner = oneLiner,
    hasMinutes = hasMinutes,
    hasTranscript = hasTranscript,
    unreadable = unreadable,
    hasAudio = hasAudio,
    duration = duration,
    speakers = emptyList(),
    tags = tags,
    archived = archived,
    sizeBytes = 0uL,
)

/**
 * JVM 单测用的引擎替身。
 *
 * [onScan] 与 [onRead] 是可控行为：要测「扫描失败」就让它们抛异常，
 * 要测数据映射就让它们返回构造好的记录。
 */
class FakeEnginePort(
    private val onScan: () -> List<MeetingSummary> = { emptyList() },
    private val onRead: (String) -> MeetingDetail = { id -> throw TingoFailure(io.github.tancheng33.tingo.engine.FailureKind.MISSING, id) },
    private val onArchive: (String, Boolean) -> Unit = { _, _ -> },
    private val onDelete: (String) -> ULong = { 0uL },
    private val onSearch: (String) -> SearchResults = { q -> SearchResults(query = q, expanded = emptyList(), hits = emptyList()) },
    private val onBatch: (List<String>, String, String) -> List<BatchOutcome> = { ids, _, _ ->
        ids.map { BatchOutcome(id = it, ok = true, error = "") }
    },
    private val onStorage: () -> StorageStats = {
        StorageStats(count = 0u, totalBytes = 0uL, audioBytes = 0uL, leftoverBytes = 0uL, largest = emptyList())
    },
) : TingoEnginePort {
    /** 归档与删除的调用记录，供用例断言"界面确实把动作传下去了"。 */
    val archived = mutableListOf<Pair<String, Boolean>>()
    val deleted = mutableListOf<String>()

    override fun engineVersion(): String = "test"

    override fun capabilities(): Capabilities = Capabilities(
        meetingStorage = true,
        transcriptParse = true,
        minutesParse = true,
        fishboneParse = true,
        speakerNaming = true,
        termsExtract = true,
    )

    override fun meetingsRoot(): String = "/fake/tingo/meetings"

    override fun scanLibrary(): List<MeetingSummary> = onScan()

    override fun readMeeting(id: String): MeetingDetail = onRead(id)

    override fun parseMinutes(text: String): MinutesDoc = MinutesDoc(title = "", sections = emptyList())

    override fun setArchived(id: String, archived: Boolean) {
        this.archived += id to archived
        onArchive(id, archived)
    }

    override fun deleteMeeting(id: String): ULong {
        deleted += id
        return onDelete(id)
    }

    override fun parseFishbone(text: String): Fishbone? = null

    override fun renameMeeting(id: String, title: String) {
        renamed += id to title
    }

    override fun setTags(id: String, tags: List<String>): List<String> {
        tagged += id to tags
        return tags
    }

    override fun batch(ids: List<String>, action: String, tag: String): List<BatchOutcome> {
        batched += Triple(ids, action, tag)
        return onBatch(ids, action, tag)
    }

    override fun search(query: String): SearchResults {
        searches += query
        return onSearch(query)
    }

    override fun storageStats(): StorageStats = onStorage()

    override fun deleteToTrash(id: String): ULong {
        trashed += id
        return 1uL
    }

    override fun restoreFromTrash(id: String) {
        restored += id
    }

    override fun listTrash(): List<TrashItem> = trash

    override fun purgeTrash(id: String): ULong {
        purged += id
        return 1uL
    }

    override fun cleanupLeftovers(): ULong {
        cleaned += 1
        return 1uL
    }

    /** 改名的调用记录 */
    val renamed = mutableListOf<Pair<String, String>>()

    /** 设置标签的调用记录 */
    val tagged = mutableListOf<Pair<String, List<String>>>()

    /** 批量操作的调用记录（ids, action, tag） */
    val batched = mutableListOf<Triple<List<String>, String, String>>()

    /** 搜索词记录 */
    val searches = mutableListOf<String>()

    /** 回收站内容：用例直接塞 */
    var trash: List<TrashItem> = emptyList()

    val trashed = mutableListOf<String>()
    val restored = mutableListOf<String>()
    val purged = mutableListOf<String>()

    /** 残留清理的调用次数 */
    var cleaned = 0
}
