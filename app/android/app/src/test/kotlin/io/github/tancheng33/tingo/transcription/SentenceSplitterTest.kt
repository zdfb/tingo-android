package io.github.tancheng33.tingo.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSplitterTest {

    @Test
    fun splitsOnSentenceFinalPunctuationOnly() {
        val sentences = SentenceSplitter.split(
            "漏斗拆开看，第一步没变。问题在第二步！要不要先修？",
            startMs = 0,
            endMs = 6_000,
        )

        assertEquals(3, sentences.size)
        assertEquals("漏斗拆开看，第一步没变。", sentences[0].text)
        assertEquals("问题在第二步！", sentences[1].text)
        assertEquals("要不要先修？", sentences[2].text)
    }

    @Test
    fun timestampsAreMonotonicAndInsideTheSegment() {
        val sentences = SentenceSplitter.split("第一句话在这里。第二句话比较长一些，还有内容。第三句。", 1_000, 7_000)

        assertEquals(3, sentences.size)
        assertEquals(1_000L, sentences.first().startMs)
        assertEquals(7_000L, sentences.last().endMs)
        sentences.zipWithNext().forEach { (a, b) ->
            assertTrue("句子之间不能重叠：$a -> $b", b.startMs >= a.startMs)
            assertTrue("结束时间不能早于开始：$a", a.endMs >= a.startMs)
        }
        sentences.forEach {
            assertTrue("时间必须落在原段内", it.startMs >= 1_000 && it.endMs <= 7_000)
        }
    }

    @Test
    fun longerSentenceGetsMoreTime() {
        val sentences = SentenceSplitter.split("短句。这一句话明显要长很多很多，需要更多时间来念完它。", 0, 10_000)

        val first = sentences[0].endMs - sentences[0].startMs
        val second = sentences[1].endMs - sentences[1].startMs
        assertTrue("长句应分到更多时长：$first vs $second", second > first)
    }

    @Test
    fun textWithoutPunctuationStaysOneSentence() {
        val sentences = SentenceSplitter.split("这句话没有标点", 500, 2_500)

        assertEquals(1, sentences.size)
        assertEquals(500L, sentences.single().startMs)
        assertEquals(2_500L, sentences.single().endMs)
    }

    @Test
    fun blankTextYieldsNothing() {
        assertTrue(SentenceSplitter.split("   ", 0, 1_000).isEmpty())
    }

    @Test
    fun consecutivePunctuationDoesNotSplitTwice() {
        val sentences = SentenceSplitter.split("真的吗？！那就这样。", 0, 4_000)

        assertEquals(2, sentences.size)
        assertEquals("真的吗？！", sentences[0].text)
    }

    @Test
    fun noTimeIsLostWhenRounding() {
        val sentences = SentenceSplitter.split("一二三四五六七八九十。十九八七六五四三二一。", 0, 999)
        val covered = sentences.sumOf { it.endMs - it.startMs }
        assertEquals(999L, covered)
    }
}
