package io.github.tancheng33.tingo.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入录音时的采样率与声道处理。
 *
 * 这两步决定了识别质量：采样率搞错或声道丢掉一路，转写出来就是含糊的半场会议。
 * 都是纯计算，在 JVM 上逐条验。
 */
class AudioResampleTest {

    @Test
    fun stereoIsAveragedNotTruncated() {
        // 同一时刻左 0.5、右 -0.5 → 均值 0；若"只取左声道"，这里会是 0.5
        val interleaved = shortArrayOf(16384, -16384, 16384, -16384)

        val mono = AudioResample.toMono(interleaved, channels = 2)

        assertEquals(2, mono.size)
        assertEquals(0f, mono[0], 1e-4f)
    }

    @Test
    fun oneSidedContentSurvivesMonoDownmix() {
        // 只有右声道有声音：取左会静音，取均值应保留一半幅度
        val interleaved = shortArrayOf(0, 16384, 0, 16384)

        val mono = AudioResample.toMono(interleaved, channels = 2)

        assertTrue("右声道内容被丢了：${mono.toList()}", mono.all { it > 0.2f })
    }

    @Test
    fun resampledLengthMatchesTheNewRate() {
        val from48k = FloatArray(48_000) { 0.1f }

        val at16k = AudioResample.resample(from48k, fromRate = 48_000)

        // 一秒的素材在任何采样率下都是"一秒"
        assertEquals(16_000, at16k.size)
    }

    @Test
    fun resampleKeepsTheWaveShape() {
        // 1 kHz 正弦 @48k，降到 16k 后仍然应该是 1 kHz（每秒 16 个周期）
        val from48k = FloatArray(4_800) { index ->
            kotlin.math.sin(2.0 * Math.PI * 1_000.0 * index / 48_000.0).toFloat()
        }

        val at16k = AudioResample.resample(from48k, fromRate = 48_000)

        // 0.1 秒的 1 kHz 素材 = 100 个周期；端点前后差一两个是插值的边界效应
        val zeroCrossings = at16k.indices.drop(1).count { at16k[it - 1] < 0f && at16k[it] >= 0f }
        assertTrue("周期数应在 100 上下：$zeroCrossings", zeroCrossings in 98..100)
    }

    @Test
    fun already16kMonoIsPassedThrough() {
        val samples = FloatArray(1_600) { 0.01f }

        val same = AudioResample.resample(samples, fromRate = AudioResample.TARGET_RATE)

        assertTrue("不该改动已经是 16k 的素材", same === samples)
    }

    @Test
    fun pcmConversionClipsInsteadOfWrapping() {
        val pcm = AudioResample.toPcm16(floatArrayOf(2f, -2f, 0.5f))

        // 越界必须夹住：溢出会变成刺耳的爆音
        assertEquals(Short.MAX_VALUE, pcm[0])
        assertEquals(Short.MIN_VALUE, pcm[1])
        assertEquals((0.5f * 32767f).toInt().toShort().toInt(), pcm[2].toInt())
    }
}
