package io.github.tancheng33.tingo.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerNamesCarryOverTest {

    @Test
    fun keepsNamesForSpeakersStillPresent() {
        val carried = carryOverSpeakerNames(mapOf(0 to "林舟", 1 to "王工"), listOf(0, 1, 0))

        assertEquals(mapOf(0 to "林舟", 1 to "王工"), carried)
    }

    @Test
    fun dropsNamesForSpeakersThatDisappeared() {
        // 新结果只剩 0、1 号说话人：2 号的名字不该再写进文件
        val carried = carryOverSpeakerNames(mapOf(0 to "林舟", 2 to "已离场"), listOf(0, 0))

        assertEquals(mapOf(0 to "林舟"), carried)
    }

    @Test
    fun emptyPreviousOrSpeakersYieldsNothing() {
        assertTrue(carryOverSpeakerNames(null, listOf(0)).isEmpty())
        assertTrue(carryOverSpeakerNames(emptyMap(), listOf(0)).isEmpty())
        assertTrue(carryOverSpeakerNames(mapOf(0 to "林舟"), emptyList()).isEmpty())
    }
}
