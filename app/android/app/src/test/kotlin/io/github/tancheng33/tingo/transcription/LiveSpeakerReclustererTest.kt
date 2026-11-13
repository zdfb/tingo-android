package io.github.tancheng33.tingo.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveSpeakerReclustererTest {
    private fun vector(value: Float) = floatArrayOf(value, 1f - value)

    @Test
    fun firstEmbeddingGetsAProvisionalSpeakerImmediately() {
        val tracker = LiveSpeakerReclusterer(reclusterEvery = 4, clusterer = { list -> List(list.size) { 0 } })
        assertEquals(1, tracker.add(1, "你好", vector(1f)).single().speaker)
    }

    @Test
    fun reclusteringCanCorrectEarlierTemporaryNumbers() {
        var calls = 0
        val tracker = LiveSpeakerReclusterer(reclusterEvery = 2, clusterer = { list ->
            calls++
            if (calls == 1) List(list.size) { 0 } else list.mapIndexed { index, _ -> if (index == 0) 1 else 0 }
        })
        tracker.add(1, "第一句", vector(1f))
        val result = tracker.add(2, "第二句", vector(0f))
        assertEquals(2, calls)
        assertEquals(listOf(2, 1), result.map { it.speaker })
        val corrected = tracker.add(3, "第三句", vector(1f)).map { it.speaker }
        assertEquals(2, calls)
        assertEquals(listOf(2, 1, null), corrected)
    }

    @Test
    fun emptyEmbeddingStaysUnassigned() {
        val tracker = LiveSpeakerReclusterer(reclusterEvery = 1, clusterer = { list -> List(list.size) { 0 } })
        assertNull(tracker.add(1, "没有声纹", FloatArray(0)).single().speaker)
    }
}
