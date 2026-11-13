package io.github.tancheng33.tingo.recording

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 会议音频到底在哪。
 *
 * 这条判断错了不会崩，只会"会议看得见、播放条不出现"——示例会议就是这么漏掉的
 * （素材是 m4a，而这里只认 wav）。
 */
class RecordingTracksTest {

    @Test
    fun concurrentMixRequestsProduceOneReadableRecording() {
        val meeting = dir()
        wav(File(meeting, "mic.wav"), 160_000)
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val results = (1..4).map {
                pool.submit { start.await(); RecordingTracks.ensureMixed(meeting) }
            }
            start.countDown()
            results.forEach { it.get() }
            assertEquals(File(meeting, "录音.wav"), RecordingTracks.find(meeting))
        } finally {
            pool.shutdownNow()
        }
    }

    private fun dir(): File = Files.createTempDirectory("tracks").toFile()

    /** 写一个头里长度与文件大小一致的 wav（"完整"的判据）。 */
    private fun wav(target: File, samples: Int = 1_600) {
        val header = io.github.tancheng33.tingo.importing.WavFile.header(samples * 2, 16_000)
        target.writeBytes(header + ByteArray(samples * 2))
    }

    @Test
    fun prefersTheMixedWav() {
        val meeting = dir()
        wav(File(meeting, "mic.wav"))
        wav(File(meeting, "录音.wav"))

        assertEquals(File(meeting, "录音.wav"), RecordingTracks.find(meeting))
    }

    @Test
    fun fallsBackToRawTrackWhenMixedIsIncomplete() {
        val meeting = dir()
        wav(File(meeting, "mic.wav"))
        // 头里长度还是 0：混音写到一半被杀
        File(meeting, "录音.wav").writeBytes(ByteArray(100))

        assertEquals(File(meeting, "mic.wav"), RecordingTracks.find(meeting))
    }

    @Test
    fun findsImportedM4aWhenThereIsNoWav() {
        val meeting = dir()
        // 示例会议与导入的会议都是原始格式（桌面端就是保留原文件）
        File(meeting, "录音.m4a").writeBytes(ByteArray(2_000))

        assertEquals(File(meeting, "录音.m4a"), RecordingTracks.find(meeting))
    }

    @Test
    fun ignoresNonAudioFilesInTheFolder() {
        val meeting = dir()
        File(meeting, "纪要.md").writeText("# 会议")
        File(meeting, "transcript.json").writeText("{}")

        assertNull(RecordingTracks.find(meeting))
    }
}
