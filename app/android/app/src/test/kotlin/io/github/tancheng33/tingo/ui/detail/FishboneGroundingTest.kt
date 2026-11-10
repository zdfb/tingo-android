package io.github.tancheng33.tingo.ui.detail

import io.github.tancheng33.tingo.engine.Fishbone
import io.github.tancheng33.tingo.engine.FishboneBranch
import io.github.tancheng33.tingo.engine.Transcript
import io.github.tancheng33.tingo.engine.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 鱼骨图的原文核对。
 *
 * 这是产品卖点"结论有出处"的落点：只画图不核对，等于把编出来的原因和有原话的原因画成一样。
 * 门限与规则要和桌面 `fishbone.ts` 一致（1 秒容差、20 字算依据很少）。
 */
class FishboneGroundingTest {

    private fun transcript(vararg turns: Triple<Long, String, String>, durationMs: Long = 60_000) =
        Transcript(
            source = "录音.wav",
            durationMs = durationMs.toULong(),
            aliases = emptyList(),
            turns = turns.map { (start, speaker, text) ->
                Turn(
                    speakerName = speaker,
                    startMs = start.toULong(),
                    endMs = (start + 5_000).toULong(),
                    text = text,
                    sentences = emptyList(),
                )
            },
        )

    private val meeting = transcript(
        Triple(0L, "说话人1", "注册页转化率掉了三个点，主要是首屏加载慢。"),
        Triple(12_000L, "说话人2", "那就先把首屏的图片挪到 CDN 上试试。"),
        Triple(30_000L, "说话人1", "好。"),
    )

    @Test
    fun aTimestampOneSecondAfterATurnStillCounts() {
        // 模型常给整秒：00:13 应该落在 00:12 那一段发言上（桌面同样是 +999ms 容差）
        val quote = FishboneGrounding.resolve("00:13", meeting)

        assertEquals(12_000L, quote?.ms)
        assertEquals("说话人2", quote?.speaker)
        assertTrue(quote!!.text.contains("CDN"))
    }

    @Test
    fun aTimestampBeyondTheRecordingIsNotEvidence() {
        assertNull(FishboneGrounding.resolve("99:00", meeting))
    }

    @Test
    fun aMalformedTimestampIsNotEvidence() {
        assertNull(FishboneGrounding.resolve("稍后", meeting))
    }

    @Test
    fun anUnknownDurationDoesNotRejectEveryRef() {
        // 时长未知（导入的 transcript.json 常是 0）时，桌面传的是 Infinity：不能拿"超出长度"把依据判没
        val unknown = transcript(Triple(0L, "说话人1", "有内容"), durationMs = 0)

        assertEquals(0L, FishboneGrounding.resolve("00:05", unknown)?.ms)
    }

    @Test
    fun splitSeparatesGroundedQuotesFromRawRefs() {
        val chips = FishboneGrounding.split(listOf("[00:12]", "00:13", "99:00", "99:00"), meeting)

        // 核对上的去重、按时间排；对不上的照原文列出（同一串只列一次）
        assertEquals(1, chips.quotes.size)
        assertEquals(listOf("99:00"), chips.unresolved)
    }

    @Test
    fun onlyMmSsShapedRefsCount() {
        // 桌面的正则是 ^\d{1,2}:\d{2}(:\d{2})?$："12点""稍后""1:2" 都不算依据
        assertNull(FishboneGrounding.resolve("12点", meeting))
        assertNull(FishboneGrounding.resolve("1:2", meeting))
        assertNull(FishboneGrounding.resolve("123:45", meeting))
    }

    @Test
    fun bracketsInRefsAreTolerated() {
        assertEquals(12_000L, FishboneGrounding.resolve("[00:12]", meeting)?.ms)
    }

    @Test
    fun aBranchWithDuplicatesCountsOnce() {
        val grounding = FishboneGrounding.ground(listOf("00:12", "[00:12]", "00:13"), meeting)

        assertEquals(1, grounding.quotes.size)
        assertEquals(FishboneGrounding.Level.OK, grounding.level)
    }

    @Test
    fun shortQuotesAreMarkedThin() {
        // "好。"只有两字：对上了原话，但依据很少（桌面 20 字门限）
        val grounding = FishboneGrounding.ground(listOf("00:30"), meeting)

        assertEquals(FishboneGrounding.Level.WEAK, grounding.level)
        assertEquals(2, grounding.chars)
    }

    @Test
    fun aBranchWithoutRefsHasNoEvidence() {
        val grounding = FishboneGrounding.ground(emptyList(), meeting)

        assertEquals(FishboneGrounding.Level.NONE, grounding.level)
        assertTrue(grounding.quotes.isEmpty())
    }

    @Test
    fun withoutATranscriptNothingCanBeGrounded() {
        val grounding = FishboneGrounding.ground(listOf("00:12"), null)

        assertEquals(FishboneGrounding.Level.NONE, grounding.level)
    }

    @Test
    fun theSummaryCountsQuotesAndEmptyBranches() {
        val fishbone = Fishbone(
            problem = "为什么转化率掉了？",
            branches = listOf(
                FishboneBranch(title = "首屏慢", detail = "图片太大", refs = listOf("00:00")),
                FishboneBranch(title = "渠道质量", detail = "", refs = listOf("00:12")),
                FishboneBranch(title = "没人管", detail = "缺少负责人", refs = emptyList()),
                // 空标题的分支不计数：桌面上同样先过滤掉
                FishboneBranch(title = "", detail = "", refs = listOf("30:00")),
            ),
        )

        val summary = FishboneGrounding.summarize(fishbone, meeting)

        assertEquals(3, summary.branches)
        assertEquals(2, summary.quotes)
        assertEquals(1, summary.withoutEvidence)
    }
}
