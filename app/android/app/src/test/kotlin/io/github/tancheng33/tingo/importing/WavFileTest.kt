package io.github.tancheng33.tingo.importing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 写出来的 wav 必须能被自家读取器与播放器认（RIFF 头字段全对、字节序对）。 */
class WavFileTest {

    @Test
    fun headerDescribesA16kMonoPcmFile() {
        val header = WavFile.header(dataBytes = 32_000, sampleRate = 16_000)

        assertEquals(44, header.size)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
        assertEquals(1, le16(header, 20)) // PCM
        assertEquals(1, le16(header, 22)) // 单声道
        assertEquals(16_000, le32(header, 24))
        assertEquals(32_000, le32(header, 28)) // byteRate = 16000*2
        assertEquals(2, le16(header, 32)) // blockAlign
        assertEquals(16, le16(header, 34))
        assertEquals(32_000, le32(header, 40))
        assertEquals(36 + 32_000, le32(header, 4))
    }

    @Test
    fun writtenFileRoundTripsThroughTheReader() {
        val target = File.createTempFile("imported", ".wav").apply { deleteOnExit() }
        val samples = ShortArray(1_600) { index -> (index % 100).toShort() }

        WavFile.write(target, samples)

        val bytes = target.readBytes()
        assertEquals(44 + samples.size * 2, bytes.size)
        // 第一个样本按小端写：低字节在前
        assertEquals((samples[0].toInt() and 0xFF).toByte(), bytes[44])
        val back = io.github.tancheng33.tingo.transcription.WavReader.format(target)
        assertEquals(16_000, back.sampleRate)
        assertEquals(samples.size * 2, back.dataBytes.toInt())
    }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(bytes: ByteArray, offset: Int): Int =
        le16(bytes, offset) or (le16(bytes, offset + 2) shl 16)

    @Test
    fun streamingWriterBackfillsTheLengthFields() {
        val target = File.createTempFile("streamed", ".wav").apply { deleteOnExit() }

        WavFile.stream(target).use { writer ->
            writer.write(ShortArray(500) { 1 })
            writer.write(ShortArray(1_100) { 2 })
        }

        val bytes = target.readBytes()
        // 总长度与 data 长度都要回填对，否则播放器读到的是 0 长度（整段静音）
        assertEquals(44 + 1_600 * 2, bytes.size)
        assertEquals(36 + 1_600 * 2, le32(bytes, 4))
        assertEquals(1_600 * 2, le32(bytes, 40))
        val format = io.github.tancheng33.tingo.transcription.WavReader.format(target)
        assertTrue("时长要算得出来：${format.durationMs}", format.durationMs > 90)
    }
}
