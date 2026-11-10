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

    /** 造 n 段声纹：前一半靠近 [a]，后一半靠近 [b]，各自带一点抖动（可复现）。 */
    private fun twoGroups(n: Int, a: FloatArray, b: FloatArray, jitter: Float = 0.02f): List<FloatArray> {
        var seed = 42L
        fun next(): Float {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 33).toFloat() / (1 shl 31).toFloat()) * 2f - 1f
        }
        return (0 until n).map { i ->
            val base = if (i % 2 == 0) a else b
            FloatArray(base.size) { base[it] + next() * jitter }
        }
    }

    @Test
    fun fewerThanTwentySegmentsIsAlwaysOneSpeaker() {
        // 与桌面同一条规则：段太少时聚类只会瞎分，宁可不分
        val many = twoGroups(19, unit(1f, 0f, 0f, 0f), unit(0f, 1f, 0f, 0f))
        assertEquals(List(19) { 0 }, SpeakerClustering().cluster(many))
    }

    @Test
    fun twoClearlyDifferentVoicesBecomeTwoSpeakers() {
        val embeddings = twoGroups(40, unit(1f, 0f, 0f, 0f), unit(0f, 1f, 0f, 0f))
        val speakers = SpeakerClustering().cluster(embeddings)

        assertEquals("两个人应该分成两簇：$speakers", 2, speakers.toSet().size)
        // 偶数下标同一个人、奇数下标同一个人
        assertEquals(1, speakers.filterIndexed { i, s -> i % 2 == 0 }.toSet().size)
        assertEquals(1, speakers.filterIndexed { i, s -> i % 2 == 1 }.toSet().size)
    }

    @Test
    fun oneSpeakerStaysOneEvenWithManySegments() {
        val embeddings = twoGroups(40, unit(1f, 0f, 0f, 0f), unit(1f, 0f, 0f, 0f), jitter = 0.01f)
        val speakers = SpeakerClustering().cluster(embeddings)

        assertEquals("同一个人的抖动不该被拆成两个：$speakers", 1, speakers.toSet().size)
    }

    @Test
    fun numberingFollowsFirstSpokenOrder() {
        val a = unit(1f, 0f, 0f, 0f)
        val b = unit(0f, 1f, 0f, 0f)
        // 让 b 先开口：把第一段换成 b
        val embeddings = listOf(b) + twoGroups(40, a, b).drop(1)
        val speakers = SpeakerClustering().cluster(embeddings)

        assertEquals("第一个开口的人编号 0", 0, speakers.first())
    }

    @Test
    fun theSameInputAlwaysGivesTheSameSpeakers() {
        val embeddings = twoGroups(60, unit(1f, 0.2f, 0f, 0f), unit(0f, 0f, 1f, 0.2f))
        val first = SpeakerClustering().cluster(embeddings)
        val second = SpeakerClustering().cluster(embeddings)

        assertEquals("k-means 固定种子：同一份声纹必须得到同样的编号", first, second)
    }

    @Test
    fun moreThanTheSpectralLimitIsStillClustered() {
        // 超过 256 段会先子采样做谱聚类，其余按余弦归档
        val embeddings = twoGroups(300, unit(1f, 0f, 0f, 0f), unit(0f, 1f, 0f, 0f))
        val speakers = SpeakerClustering().cluster(embeddings)

        assertEquals(2, speakers.toSet().size)
    }

    @Test
    fun emptyInputGivesEmptyResult() {
        assertEquals(emptyList<Int>(), SpeakerClustering().cluster(emptyList()))
    }

    @Test
    fun zeroVectorIsTolerated() {
        val embeddings = List(20) { FloatArray(4) } + listOf(unit(1f, 0f, 0f, 0f))
        val speakers = SpeakerClustering().cluster(embeddings)

        assertEquals(21, speakers.size)
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
