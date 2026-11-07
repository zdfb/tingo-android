package io.github.tancheng33.tingo.recording

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 音频到底在哪：正常结束看 录音.wav，被杀了就看残留的原始音轨。 */
class RecordingTracksTest {

    private fun dir(): File = Files.createTempDirectory("tingo-tracks").toFile()

    private fun track(dir: File, name: String, sampleRate: Int = 16_000, channels: Int = 1): File =
        File(dir, name).also { file ->
            WavWriter(file, sampleRate, channels = channels).apply {
                append(ByteArray(3_200))
                close()
            }
        }

    @Test
    fun prefersTheMixedRecording() {
        val meeting = dir()
        track(meeting, RecordingTracks.MIXED)
        track(meeting, TrackMerger.MIC_TRACK)

        assertEquals(RecordingTracks.MIXED, RecordingTracks.find(meeting)?.name)
    }

    @Test
    fun fallsBackToRawMicTrackWhenProcessWasKilled() {
        val meeting = dir()
        track(meeting, TrackMerger.MIC_TRACK)

        assertEquals(TrackMerger.MIC_TRACK, RecordingTracks.find(meeting)?.name)
    }

    @Test
    fun noAudioAtAllReturnsNull() {
        assertNull(RecordingTracks.find(dir()))
    }

    @Test
    fun headerOnlyFileIsNotAudio() {
        val meeting = dir()
        File(meeting, RecordingTracks.MIXED).writeBytes(ByteArray(44))

        assertNull(RecordingTracks.find(meeting))
    }

    @Test
    fun ensureMixedSynthesisesRecordingFromRawTracks() {
        val meeting = dir()
        track(meeting, TrackMerger.MIC_TRACK)
        track(meeting, TrackMerger.SYSTEM_TRACK, sampleRate = 48_000, channels = 2)

        val mixed = RecordingTracks.ensureMixed(meeting)

        assertTrue("应合成 录音.wav", mixed != null && mixed.isFile)
        assertEquals(RecordingTracks.MIXED, RecordingTracks.find(meeting)?.name)
        // 合成结果必须是引擎与转写都认的 16 kHz 单声道
        val pcm = io.github.tancheng33.tingo.transcription.WavReader.read(mixed!!)
        assertEquals(16_000, pcm.sampleRate)
    }

    @Test
    fun ensureMixedDoesNothingWhenRecordingAlreadyExists() {
        val meeting = dir()
        track(meeting, RecordingTracks.MIXED)
        track(meeting, TrackMerger.MIC_TRACK)

        assertNull(RecordingTracks.ensureMixed(meeting))
    }

    @Test
    fun ensureMixedWithoutAnyTrackReturnsNull() {
        assertNull(RecordingTracks.ensureMixed(dir()))
    }
}
