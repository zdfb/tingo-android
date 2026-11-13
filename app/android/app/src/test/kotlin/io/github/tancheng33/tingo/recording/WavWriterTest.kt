package io.github.tancheng33.tingo.recording

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WavWriterTest {

    @Test
    fun padsStereoTrackToRecordingPositionWithoutOverwritingAudio() {
        val file = tempFile()
        WavWriter(file, 48_000, channels = 2).use { writer ->
            writer.padSilenceToDuration(250)
            writer.append(byteArrayOf(1, 2, 3, 4))
            writer.padSilenceToDuration(100)
            assertEquals(48_004L, writer.dataBytes)
        }
        val bytes = file.readBytes()
        assertTrue(bytes.copyOfRange(44, 48_044).all { it == 0.toByte() })
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), bytes.copyOfRange(48_044, 48_048))
    }

    private fun tempFile(): File = Files.createTempFile("tingo-wav", ".wav").toFile().apply { delete() }

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    @Test
    fun headerDescribesTheAudioItWillContain() {
        val file = tempFile()
        WavWriter(file).use { writer ->
            // 满一秒：16000 样本 × 2 字节
            writer.append(ByteArray(32_000))
            assertEquals(1000L, writer.durationMs())
        }

        val bytes = file.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals(16, le32(bytes, 16)) // fmt 长度
        assertEquals(1, le16(bytes, 20)) // PCM
        assertEquals(1, le16(bytes, 22)) // 单声道
        assertEquals(16_000, le32(bytes, 24)) // 采样率
        assertEquals(32_000, le32(bytes, 28)) // 字节率
        assertEquals(2, le16(bytes, 32)) // 块对齐
        assertEquals(16, le16(bytes, 34)) // 位深
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals(32_000, le32(bytes, 40)) // data 长度
        assertEquals(44 + 32_000, bytes.size)
    }

    @Test
    fun closeIsIdempotent() {
        // 进程被杀后恢复流程会再调一次 close，不能因此破坏文件
        val file = tempFile()
        val writer = WavWriter(file)
        writer.append(ByteArray(200))
        writer.close()
        writer.close()
        assertEquals(44 + 200, file.length())
    }

    @Test
    fun appendRejectsHalfSamples() {
        val file = tempFile()
        val writer = WavWriter(file)
        val failure = runCatching { writer.append(ByteArray(3)) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        writer.close()
        file.delete()
    }

    @Test
    fun durationFollowsBytesNotClock() {
        val file = tempFile()
        WavWriter(file).use { writer ->
            writer.append(ByteArray(16_000)) // 0.5 秒
            assertEquals(500L, writer.durationMs())
        }
        file.delete()
    }

    @Test
    fun repairRestoresHeaderOfInterruptedRecording() {
        val file = tempFile()
        // 模拟"进程被杀"：只有数据，头里的长度是 0
        WavWriter(file, existingDataBytes = 0).apply { }
        file.writeBytes(ByteArray(44) + ByteArray(4_800)) // 数据部分 0.15 秒
        val duration = WavWriter.repair(file)

        assertEquals(150L, duration)
        val bytes = file.readBytes()
        assertEquals(4_800, le32(bytes, 40))
        assertEquals(36 + 4_800, le32(bytes, 4))
        file.delete()
    }

    @Test
    fun repairedFileIsAppendable() {
        val file = tempFile()
        // 已有 0.5 秒，再补 0.5 秒，最终应是一秒且头部长度对得上
        file.writeBytes(ByteArray(44) + ByteArray(16_000))
        WavWriter.repair(file)

        WavWriter(file, existingDataBytes = 16_000).use { writer ->
            writer.append(ByteArray(16_000))
            assertEquals(1_000L, writer.durationMs())
        }
        assertEquals(44 + 32_000, file.length())
        file.delete()
    }

    @Test
    fun payloadRoundTrips() {
        val file = tempFile()
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6)
        WavWriter(file).use { it.append(pcm) }
        val bytes = file.readBytes()
        assertArrayEquals(pcm, bytes.copyOfRange(44, 50))
        assertFalse(bytes.copyOfRange(0, 4).contentEquals(pcm))
        file.delete()
    }
}
