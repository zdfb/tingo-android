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
