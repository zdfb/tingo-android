package io.github.tancheng33.tingo.recording

import java.io.File
import java.io.RandomAccessFile

/**
 * 16 bit / 单声道 / 16 kHz 的 wav 写入器。
 *
 * 录音是"边录边写"：开始时就落一个 44 字节头（长度字段先写 0），结束后回填字节数与时长。
 * 这个类不碰 Android，任何在写 wav 的地方都能用，也方便在 JVM 单测里逐字节校验头部。
 */
class WavWriter(
    private val file: File,
    val sampleRate: Int = SAMPLE_RATE,
    /** 只为"修复已有文件"而存在：把已写入的 PCM 字节数认下来，不截断文件 */
    private val existingDataBytes: Long? = null,
) : AutoCloseable {

    private val out = RandomAccessFile(file, "rw").apply {
        if (existingDataBytes == null) {
            setLength(0)
            write(header(dataBytes = 0))
        }
    }

    /** 已经写入的 PCM 字节数。 */
    var dataBytes: Long = existingDataBytes ?: 0
        private set

    val isOpen: Boolean
        get() = !closed
    private var closed = false

    /** 追加一段 PCM（16 bit little-endian、单声道）。 */
    fun append(pcm: ByteArray, length: Int = pcm.size) {
        require(!closed) { "wav 已关闭" }
        require(length % BYTES_PER_SAMPLE == 0) { "PCM 字节数必须是 $BYTES_PER_SAMPLE 的整数倍" }
        out.seek(HEADER_SIZE + dataBytes)
        out.write(pcm, 0, length)
        dataBytes += length
    }

    /** 录音时长（毫秒），由已写入的字节数换算，不依赖系统时钟。 */
    fun durationMs(): Long = dataBytes * 1000 / (sampleRate.toLong() * BYTES_PER_SAMPLE)

    /** 回填长度字段并关闭；重复调用无副作用（进程被杀后由恢复流程再调一次）。 */
    override fun close() {
        if (closed) return
        closed = true
        out.seek(0)
        out.write(header(dataBytes))
        out.close()
    }

    private fun header(dataBytes: Long): ByteArray {
        val dataSize = (dataBytes and 0xFFFFFFFFL).toInt()
        val byteRate = sampleRate * BYTES_PER_SAMPLE
        val buf = ByteArray(HEADER_SIZE)
        var i = 0
        fun ascii(s: String) { for (c in s) buf[i++] = c.code.toByte() }
        fun le32(v: Int) {
            buf[i++] = (v and 0xFF).toByte()
            buf[i++] = ((v ushr 8) and 0xFF).toByte()
            buf[i++] = ((v ushr 16) and 0xFF).toByte()
            buf[i++] = ((v ushr 24) and 0xFF).toByte()
        }
        fun le16(v: Int) {
            buf[i++] = (v and 0xFF).toByte()
            buf[i++] = ((v ushr 8) and 0xFF).toByte()
        }

        ascii("RIFF")
        le32(36 + dataSize)
        ascii("WAVE")
        ascii("fmt ")
        le32(16) // fmt 块长度
        le16(1) // PCM
        le16(CHANNELS)
        le32(sampleRate)
        le32(byteRate)
        le16(BYTES_PER_SAMPLE) // 块对齐
        le16(16) // 位深
        ascii("data")
        le32(dataSize)
        return buf
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BYTES_PER_SAMPLE = 2
        const val HEADER_SIZE = 44

        /** 进程被杀后，把没来得及收尾的 wav 头补好；返回实际时长（毫秒）。 */
        fun repair(file: File, sampleRate: Int = SAMPLE_RATE): Long {
            val dataBytes = (file.length() - HEADER_SIZE).coerceAtLeast(0)
            WavWriter(file, sampleRate, existingDataBytes = dataBytes).close()
            return dataBytes * 1000 / (sampleRate.toLong() * BYTES_PER_SAMPLE)
        }
    }
}
