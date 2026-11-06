package io.github.tancheng33.tingo.recording

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMixTest {

    @Test
    fun bytesAndSamplesRoundTrip() {
        val samples = floatArrayOf(0f, 0.5f, -0.5f, 0.999f, -0.999f)
        val bytes = AudioMix.samplesToBytes(samples)
        val back = AudioMix.bytesToSamples(bytes)

        assertEquals(samples.size, back.size)
        samples.forEachIndexed { i, expected ->
            assertEquals(expected, back[i], 0.0001f)
        }
    }

    @Test
    fun clippingHappensAtFullScale() {
        val bytes = AudioMix.samplesToBytes(floatArrayOf(2f, -2f))

        // 1.0 → 32767，-1.0 → -32767
        assertEquals(32_767, (bytes[0].toInt() and 0xFF) or (bytes[1].toInt() shl 8))
        assertEquals(-32_767, (bytes[2].toInt() and 0xFF) or (bytes[3].toInt() shl 8))
    }

    @Test
    fun stereoDownmixAveragesBothChannels() {
        val mono = AudioMix.stereoToMono(floatArrayOf(1f, 0f, 0.5f, 0.5f))

        assertEquals(0.5f, mono[0], 0.0001f)
        assertEquals(0.5f, mono[1], 0.0001f)
    }

    @Test
    fun resampleHalvesTheSampleCountForDoubleRate() {
        val samples = FloatArray(48_000) { 0.25f }
        val out = AudioMix.resample(samples, fromRate = 48_000, toRate = 16_000)

        assertEquals(16_000, out.size)
        // 常数信号重采样后仍然是同一个值（不会引入 DC 偏移）
        out.forEach { assertEquals(0.25f, it, 0.0005f) }
    }

    @Test
    fun resampleKeepsRampShape() {
        val ramp = FloatArray(1_000) { it / 1_000f }
        val out = AudioMix.resample(ramp, fromRate = 48_000, toRate = 16_000)

        assertTrue(
            "单调上升的信号重采样后仍应单调",
            out.toList().zipWithNext().all { (a, b) -> b >= a - 0.0001f },
        )
        assertTrue("首尾幅度不能变", out.first() < 0.01f && out.last() > 0.9f)
    }

    @Test
    fun sameRateIsPassThrough() {
        val samples = floatArrayOf(0.1f, 0.2f)
        assertTrue(AudioMix.resample(samples, 16_000, 16_000) === samples)
    }

    @Test
    fun mixingAddsTracksAndClips() {
        val a = floatArrayOf(0.4f, 0.8f, 0f)
        val b = floatArrayOf(0.4f, 0.8f, 0f)

        val mixed = AudioMix.mix(listOf(a, b))

        assertEquals(0.8f, mixed[0], 0.0001f)
        assertEquals(1f, mixed[1], 0.0001f) // 1.6 削顶到 1.0
        assertEquals(0f, mixed[2], 0.0001f)
    }

    @Test
    fun mixingDifferentLengthsUsesTheLongest() {
        val mixed = AudioMix.mix(listOf(floatArrayOf(0.5f), floatArrayOf(0.1f, 0.1f, 0.1f)))

        assertEquals(3, mixed.size)
        assertEquals(0.6f, mixed[0], 0.0001f)
        assertEquals(0.1f, mixed[2], 0.0001f)
    }

    private fun dir(): File = Files.createTempDirectory("tingo-tracks").toFile()

    @Test
    fun mergerCombinesMicAndSystemTracks() {
        val meeting = dir()
        val mic = File(meeting, TrackMerger.MIC_TRACK)
        val system = File(meeting, TrackMerger.SYSTEM_TRACK)
        WavWriter(mic, 16_000).apply { append(AudioMix.samplesToBytes(FloatArray(16_000) { 0.3f })); close() }
        // 系统音轨是 48 kHz 立体声：混音前要先降采样与降声道
        val stereo = FloatArray(48_000 * 2) { 0.3f }
        WavWriter(system, 48_000, channels = 2).apply { append(AudioMix.samplesToBytes(stereo)); close() }

        val mixed = TrackMerger.mixInto(meeting)!!

        val pcm = io.github.tancheng33.tingo.transcription.WavReader.read(mixed)
        assertEquals(16_000, pcm.sampleRate)
        assertEquals(16_000, pcm.samples.size)
        assertEquals(0.6f, pcm.samples[100], 0.01f)
    }

    @Test
    fun mergerReturnsNullWithoutTracks() {
        assertEquals(null, TrackMerger.mixInto(dir()))
    }

    @Test
    fun mergerWorksWithOnlyTheMicTrack() {
        val meeting = dir()
        WavWriter(File(meeting, TrackMerger.MIC_TRACK), 16_000).apply {
            append(AudioMix.samplesToBytes(FloatArray(8_000) { 0.1f }))
            close()
        }

        val mixed = TrackMerger.mixInto(meeting)!!

        assertEquals(8_000, io.github.tancheng33.tingo.transcription.WavReader.read(mixed).samples.size)
    }
}
