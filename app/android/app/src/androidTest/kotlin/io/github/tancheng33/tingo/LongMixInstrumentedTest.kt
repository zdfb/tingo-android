package io.github.tancheng33.tingo

import androidx.test.platform.app.InstrumentationRegistry
import io.github.tancheng33.tingo.recording.TrackMerger
import io.github.tancheng33.tingo.recording.WavWriter
import io.github.tancheng33.tingo.transcription.WavReader
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长录音的混音内存边界。
 *
 * 规格要求"长录音 MUST NOT 一次性把整段音频读入内存"。单测里只用 2 秒素材，
 * 证明不了这一点；这里在真机上用 10 分钟双轨（约 115 MB 原始数据）跑一次，
 * 并把混音过程的堆增长卡在一个上限内——整段读入的实现会在这里直接 OOM。
 */
class LongMixInstrumentedTest {

    @Test
    fun mixesTenMinuteTwoTrackRecordingWithoutBlowingUpMemory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "long-mix").apply { deleteRecursively(); mkdirs() }
        val minutes = 10
        val micFrames = 16_000 * 60 * minutes
        val systemFrames = 48_000 * 60 * minutes

        writeSilence(File(dir, TrackMerger.MIC_TRACK), 16_000, 1, micFrames)
        writeSilence(File(dir, TrackMerger.SYSTEM_TRACK), 48_000, 2, systemFrames)

        val before = usedHeapBytes()
        val mixed = TrackMerger.mixInto(dir)
        val after = usedHeapBytes()

        assertTrue("应合成 录音.wav", mixed != null && mixed.isFile)
        val pcm = WavReader.format(mixed!!)
        assertTrue("时长应接近 10 分钟，实际 ${pcm.durationMs}", pcm.durationMs in 595_000..605_000)
        val growthMb = (after - before) / (1024 * 1024)
        assertTrue("混音不应把整段音频读进内存（堆增长 ${growthMb}MB）", growthMb < 80)
        dir.deleteRecursively()
    }

    /** 写一段静音但长度真实的音轨，避免把测试时间花在编码上。 */
    private fun writeSilence(file: File, rate: Int, channels: Int, frames: Int) {
        val writer = WavWriter(file, rate, channels = channels)
        val chunk = ByteArray(rate * channels * 2) // 1 秒
        var written = 0
        while (written < frames) {
            val n = minOf(rate, frames - written)
            writer.append(chunk, n * channels * 2)
            written += n
        }
        writer.close()
    }

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }
}
