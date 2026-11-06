package io.github.tancheng33.tingo.testing

import io.github.tancheng33.tingo.engine.Capabilities
import io.github.tancheng33.tingo.engine.Fishbone
import io.github.tancheng33.tingo.engine.MeetingDetail
import io.github.tancheng33.tingo.engine.MeetingSummary
import io.github.tancheng33.tingo.engine.MinutesDoc
import io.github.tancheng33.tingo.engine.TingoEnginePort
import io.github.tancheng33.tingo.engine.TingoFailure

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
) : TingoEnginePort {
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

    override fun parseFishbone(text: String): Fishbone? = null
}
