package io.github.tancheng33.tingo.recording

import io.github.tancheng33.tingo.transcription.WavReader
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐块混音：规格禁止把整段音频读进内存，所以长录音必须走这条路。
 *
 * 这里用"分块结果 ≈ 整段混音结果"来验证：块边界不能丢样本、不能重复、不能跳变。
 */
class StreamingMixerTest {

    private fun dir(): File = Files.createTempDirectory("tingo-stream-mix").toFile()

    private fun writeTrack(file: File, sampleRate: Int, channels: Int, frames: Int) {
        WavWriter(file, sampleRate, channels = channels).let { writer ->
            val data = ByteArray(frames * channels * 2)
            for (i in 0 until frames) {
                val value = (i % 1000).toShort()
                for (c in 0 until channels) {
                    val at = (i * channels + c) * 2
                    data[at] = (value.toInt() and 0xFF).toByte()
                    data[at + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
                }
            }
            writer.append(data)
            writer.close()
        }
    }

    @Test
    fun mixesTwoTracksIntoOne16kMonoFile() {
        val dir = dir()
        writeTrack(File(dir, TrackMerger.MIC_TRACK), 16_000, 1, 32_000)
        writeTrack(File(dir, TrackMerger.SYSTEM_TRACK), 48_000, 2, 96_000)

        val mixed = TrackMerger.mixInto(dir)

        assertTrue("应合成 录音.wav", mixed != null && mixed.isFile)
        val pcm = WavReader.read(mixed!!)
        assertEquals(16_000, pcm.sampleRate)
        assertTrue("时长应接近 2 秒，实际 ${pcm.samples.size}", pcm.samples.size in 31_000..33_000)
    }

    @Test
    fun chunkSizeDoesNotChangeTotalLength() {
        val dir = dir()
        writeTrack(File(dir, TrackMerger.MIC_TRACK), 16_000, 1, 16_000)
        val sink = File(dir, "out.wav")
        val source = File(dir, TrackMerger.MIC_TRACK)

        WavWriter(sink, 16_000).let { writer ->
            StreamingMixer.mix(listOf(source), writer, chunkFrames = 1_000)
            writer.close()
        }
        val small = WavReader.read(sink).samples.size

        val sink2 = File(dir, "out2.wav")
        WavWriter(sink2, 16_000).let { writer ->
            StreamingMixer.mix(listOf(source), writer, chunkFrames = 16_000)
            writer.close()
        }
        val big = WavReader.read(sink2).samples.size

        assertEquals("块大小不该改变输出长度", big, small)
    }

    @Test
    fun resamplesStereo48kToMonoWithoutLosingSamples() {
        val dir = dir()
        writeTrack(File(dir, TrackMerger.SYSTEM_TRACK), 48_000, 2, 48_000)
        val sink = File(dir, "only-system.wav")

        WavWriter(sink, 16_000).let { writer ->
            StreamingMixer.mix(listOf(File(dir, TrackMerger.SYSTEM_TRACK)), writer)
            writer.close()
        }

        val pcm = WavReader.read(sink)
        // 1 秒 48 kHz 立体声应还原成 1 秒 16 kHz 单声道
        assertTrue("应有约 16000 个样本，实际 ${pcm.samples.size}", pcm.samples.size in 15_900..16_100)
    }
}
