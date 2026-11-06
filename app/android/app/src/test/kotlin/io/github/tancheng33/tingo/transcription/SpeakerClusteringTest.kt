package io.github.tancheng33.tingo.transcription

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerClusteringTest {

    private fun unit(vararg values: Float): FloatArray {
        val norm = kotlin.math.sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(values.size) { values[it] / norm }
    }

    @Test
    fun twoClearlyDifferentVoicesBecomeTwoSpeakers() {
        val a1 = unit(1f, 0.1f, 0f, 0.2f)
        val a2 = unit(0.9f, 0.15f, 0.05f, 0.2f)
        val b1 = unit(0f, 0.1f, 1f, 0.2f)
        val b2 = unit(0.05f, 0.1f, 0.95f, 0.15f)

        val speakers = SpeakerClustering().cluster(listOf(a1, b1, a2, b2))

        assertEquals(2, speakers.toSet().size)
        // 首次发言顺序：0 号其实是被第一个出现的人占用
        assertEquals(speakers[0], speakers[2])
        assertEquals(speakers[1], speakers[3])
        assertTrue(speakers[0] != speakers[1])
    }

    @Test
    fun singleSpeakerStaysOne() {
        val s = listOf(unit(1f, 0f, 0f), unit(0.95f, 0.1f, 0f), unit(0.9f, 0.2f, 0f))
        assertEquals(listOf(0, 0, 0), SpeakerClustering().cluster(s))
    }

    @Test
    fun dissimilarVectorsAllStayApart() {
        val s = listOf(unit(1f, 0f, 0f, 0f), unit(0f, 1f, 0f, 0f), unit(0f, 0f, 1f, 0f))
        val speakers = SpeakerClustering(threshold = 0.99f).cluster(s)
        assertEquals(listOf(0, 1, 2), speakers)
    }

    @Test
    fun numberingFollowsFirstSpokenOrder() {
        val b = unit(0f, 1f, 0f)
        val a = unit(1f, 0f, 0f)
        val speakers = SpeakerClustering().cluster(listOf(b, a, b, a))
        // 第一个开口的人编号 0，哪怕它和第 0 个向量并不相同
        assertEquals(0, speakers[0])
        assertEquals(1, speakers[1])
    }

    @Test
    fun emptyInputGivesEmptyResult() {
        assertEquals(emptyList<Int>(), SpeakerClustering().cluster(emptyList()))
    }

    @Test
    fun zeroVectorIsTolerated() {
        val speakers = SpeakerClustering().cluster(listOf(FloatArray(3), unit(1f, 0f, 0f)))
        assertEquals(2, speakers.size)
    }
}

class WavReaderTest {

    private fun pcm16(vararg samples: Int): ByteArray = ByteArray(samples.size * 2) { i ->
        val v = samples[i / 2]
        if (i % 2 == 0) (v and 0xFF).toByte() else ((v shr 8) and 0xFF).toByte()
    }

    private fun wav(samples: IntArray, sampleRate: Int = 16_000): java.io.File {
        val f = java.nio.file.Files.createTempFile("tingo-read", ".wav").toFile()
        val writer = io.github.tancheng33.tingo.recording.WavWriter(f, sampleRate)
        writer.append(pcm16(*samples))
        writer.close()
        f.deleteOnExit()
        return f
    }

    @Test
    fun readsSamplesAndSampleRate() {
        val pcm = WavReader.read(wav(intArrayOf(0, 16_384, -16_384, 32_767)))

        assertEquals(16_000, pcm.sampleRate)
        assertEquals(4, pcm.samples.size)
        assertEquals(0f, pcm.samples[0], 0.0001f)
        assertEquals(0.5f, pcm.samples[1], 0.0001f)
        assertEquals(-0.5f, pcm.samples[2], 0.0001f)
        assertEquals(0.99997f, pcm.samples[3], 0.0001f)
    }

    @Test
    fun durationComesFromSamplesNotHeader() {
        val pcm = WavReader.read(wav(IntArray(1_600))) // 0.1 秒
        assertEquals(100L, pcm.durationMs)
    }

    @Test
    fun rejectsNonMonoOrNon16Bit() {
        val f = java.nio.file.Files.createTempFile("tingo-bad", ".wav").toFile()
        f.writeBytes(ByteArray(44).also {
            "RIFF".toByteArray().copyInto(it, 0)
            "WAVE".toByteArray().copyInto(it, 8)
            it[22] = 2 // 双声道
        })
        val failure = runCatching { WavReader.read(f) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        f.delete()
    }

    @Test
    fun roundTripsWriterOutput() {
        val samples = IntArray(320) { if (it % 2 == 0) 1_000 else -1_000 }
        val pcm = WavReader.read(wav(samples))
        assertArrayEquals(FloatArray(320) { if (it % 2 == 0) 1_000 / 32_768f else -1_000 / 32_768f }, pcm.samples, 0.0001f)
    }
}
