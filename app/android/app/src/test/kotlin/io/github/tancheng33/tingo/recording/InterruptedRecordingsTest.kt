package io.github.tancheng33.tingo.recording

import java.io.File
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterruptedRecordingsTest {

    private fun root(): File = Files.createTempDirectory("tingo-repair").toFile()

    /**
     * 造一个"没来得及收尾"的 wav：头写好了但长度字段还是 0，数据已经落盘。
     *
     * 用 WavWriter 但不 close()，正是进程被杀时的样子。
     */
    private fun halfWritten(meeting: File, samples: Int): File {
        meeting.mkdirs()
        val wav = File(meeting, "录音.wav")
        WavWriter(wav, 16_000).append(ByteArray(samples * 2))
        return wav
    }

    private fun declaredDataBytes(wav: File): Long {
        val header = ByteArray(44)
        wav.inputStream().use { it.read(header) }
        return ByteBuffer.wrap(header, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL
    }

    @Test
    fun repairsHalfWrittenRecordingOnStartup() {
        val meetings = root()
        val wav = halfWritten(File(meetings, "2026-11-01_0900_复盘"), 1_600)

        assertTrue("半截录音应被识别", InterruptedRecordings.needsRepair(wav))
        assertEquals(1, InterruptedRecordings.repairUnder(meetings))

        assertFalse("修好之后不该再报需要修复", InterruptedRecordings.needsRepair(wav))
        assertEquals(3_200L, declaredDataBytes(wav))
    }

    @Test
    fun repairsRawMicTrackWrittenDuringRecording() {
        val meetings = root()
        val dir = File(meetings, "2026-11-01_0900_复盘").apply { mkdirs() }
        // 录制中写的是 mic.wav；进程被杀时它没有收尾，正是这条恢复要管的对象
        val mic = File(dir, "mic.wav")
        WavWriter(mic, 16_000).append(ByteArray(800))

        assertTrue(InterruptedRecordings.needsRepair(mic))
        assertEquals(1, InterruptedRecordings.repairUnder(meetings))
        assertFalse(InterruptedRecordings.needsRepair(mic))
        assertEquals(800L, declaredDataBytes(mic))
    }

    @Test
    fun repairsSystemTrackToo() {
        val meetings = root()
        val dir = File(meetings, "2026-11-01_1000_内录").apply { mkdirs() }
        val system = File(dir, "system.wav")
        WavWriter(system, 48_000, channels = 2).append(ByteArray(1_600))

        assertEquals(1, InterruptedRecordings.repairUnder(meetings))
        assertFalse(InterruptedRecordings.needsRepair(system))
    }

    @Test
    fun crashedRecordingBecomesTranscribable() {
        val meetings = root()
        val dir = File(meetings, "2026-11-01_0900_被杀").apply { mkdirs() }
        val mic = File(dir, "mic.wav")
        WavWriter(mic, 16_000).append(ByteArray(1_600))

        assertEquals(1, InterruptedRecordings.repairUnder(meetings))

        // 规格要求"可播放、可转写"：补好头之后 mic.wav 本身就是完整音轨，
        // 转写与播放通过 RecordingTracks.find 回落到它——不需要在启动时混音
        assertFalse("头应被补好", InterruptedRecordings.needsRepair(mic))
        assertEquals("mic.wav", RecordingTracks.find(dir)?.name)
    }

    @Test
    fun finishedRecordingIsLeftAlone() {
        val meetings = root()
        val meeting = File(meetings, "2026-11-01_1000_正常").apply { mkdirs() }
        val wav = File(meeting, "录音.wav")
        WavWriter(wav, 16_000).apply { append(ByteArray(3_200)); close() }

        assertFalse(InterruptedRecordings.needsRepair(wav))
        assertEquals(0, InterruptedRecordings.repairUnder(meetings))
    }

    @Test
    fun missingRootOrEmptyDirsAreHarmless() {
        val empty = root()
        assertEquals(0, InterruptedRecordings.repairUnder(empty))
        File(empty, "2026-11-01_1100_空").mkdirs()
        assertEquals(0, InterruptedRecordings.repairUnder(empty))
        assertEquals(0, InterruptedRecordings.repairUnder(File(empty, "不存在")))
    }
}

/** 补头不能改变音频的采样率/声道，否则就是把文件改坏。 */
class WavRepairFormatTest {

    private fun dir(): File = Files.createTempDirectory("tingo-repair-fmt").toFile()

    @Test
    fun repairingStereo48kKeepsItsFormat() {
        val file = File(dir(), "system.wav")
        // 48 kHz 立体声，录制中被杀：头里的长度还是 0
        WavWriter(file, 48_000, channels = 2).append(ByteArray(48_000 * 2 * 2))

        val durationMs = WavWriter.repair(file)

        val pcm = io.github.tancheng33.tingo.transcription.WavReader.readAnyChannels(file)
        assertEquals(48_000, pcm.sampleRate)
        // 1 秒的立体声数据：每声道 48000 帧
        assertEquals(48_000, pcm.samples.size)
        assertEquals(1_000L, durationMs)
    }

    @Test
    fun repairingMono16kKeepsItsFormat() {
        val file = File(dir(), "mic.wav")
        WavWriter(file, 16_000).append(ByteArray(16_000))

        val durationMs = WavWriter.repair(file)

        assertEquals(16_000, io.github.tancheng33.tingo.transcription.WavReader.read(file).sampleRate)
        assertEquals(500L, durationMs)
    }

    @Test
    fun headerlessFileFallsBackToDefaults() {
        val file = File(dir(), "broken.wav")
        file.writeBytes(ByteArray(0))

        // 空文件补头：按默认 16 kHz 单声道，不崩
        assertEquals(0L, WavWriter.repair(file))
        assertEquals(16_000, io.github.tancheng33.tingo.transcription.WavReader.read(file).sampleRate)
    }
}
