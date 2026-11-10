package io.github.tancheng33.tingo.recording

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMixTest {

    @Test
    fun clippingHappensAtFullScale() {
        val bytes = AudioMix.samplesToBytes(floatArrayOf(2f, -2f))

        // 1.0 → 32767，-1.0 → -32767
        assertEquals(32_767, (bytes[0].toInt() and 0xFF) or (bytes[1].toInt() shl 8))
        assertEquals(-32_767, (bytes[2].toInt() and 0xFF) or (bytes[3].toInt() shl 8))
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
