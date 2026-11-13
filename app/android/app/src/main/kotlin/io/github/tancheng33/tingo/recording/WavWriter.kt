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
    /** 声道数。系统内录是立体声，麦克风是单声道；默认单声道 */
    private val channels: Int = CHANNELS,
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
    @Synchronized fun append(pcm: ByteArray, length: Int = pcm.size) {
        require(!closed) { "wav 已关闭" }
        require(length % BYTES_PER_SAMPLE == 0) { "PCM 字节数必须是 $BYTES_PER_SAMPLE 的整数倍" }
        out.seek(HEADER_SIZE + dataBytes)
        out.write(pcm, 0, length)
        dataBytes += length
    }

    /** 录音时长（毫秒），由已写入的字节数换算，不依赖系统时钟。 */
    @Synchronized fun durationMs(): Long = dataBytes * 1000 / (sampleRate.toLong() * BYTES_PER_SAMPLE * channels)

    /** Insert silence until this track reaches a shared recording timeline position. */
    @Synchronized fun padSilenceToDuration(targetMs: Long) {
        val targetFrames = targetMs.coerceAtLeast(0) * sampleRate / 1000
        val currentFrames = dataBytes / (BYTES_PER_SAMPLE * channels)
        var remainingBytes = (targetFrames - currentFrames).coerceAtLeast(0) * BYTES_PER_SAMPLE * channels
        val silence = ByteArray(64 * 1024)
        while (remainingBytes > 0) {
            val count = minOf(remainingBytes, silence.size.toLong()).toInt()
            append(silence, count)
            remainingBytes -= count
        }
    }

    /** 回填长度字段并关闭；重复调用无副作用（进程被杀后由恢复流程再调一次）。 */
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try {
            out.seek(0)
            out.write(header(dataBytes))
        } finally {
            out.close()
        }
    }

    private fun header(dataBytes: Long): ByteArray {
        val dataSize = (dataBytes and 0xFFFFFFFFL).toInt()
        val byteRate = sampleRate * BYTES_PER_SAMPLE * channels
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
        le16(channels)
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

        /**
         * 进程被杀后，把没来得及收尾的 wav 头补好；返回实际时长（毫秒）。
         *
         * 保留原文件的采样率与声道数：`system.wav` 是 48 kHz 立体声，按默认的 16 kHz 单声道
         * 重写头会让它变成"6 倍速的怪声"，而且时长算错——补头不能改变音频本身的含义。
         */
        fun repair(file: File, fallbackSampleRate: Int = SAMPLE_RATE): Long {
            val format = readFormat(file)
            val sampleRate = format?.first ?: fallbackSampleRate
            val channels = format?.second ?: CHANNELS
            val dataBytes = (file.length() - HEADER_SIZE).coerceAtLeast(0)
            WavWriter(file, sampleRate, channels, existingDataBytes = dataBytes).close()
            return dataBytes * 1000 / (sampleRate.toLong() * BYTES_PER_SAMPLE * channels)
        }

        /** 从已有头里读 (采样率, 声道数)；头不完整时返回 null。 */
        private fun readFormat(file: File): Pair<Int, Int>? {
            if (file.length() < HEADER_SIZE) return null
            val header = ByteArray(HEADER_SIZE)
            val read = file.inputStream().use { it.readNBytes(header, 0, HEADER_SIZE) }
            if (read < HEADER_SIZE) return null
            if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF") return null
            if (String(header, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            val channels = u16(header, 22)
            val rate = (header[24].toInt() and 0xFF) or
                ((header[25].toInt() and 0xFF) shl 8) or
                ((header[26].toInt() and 0xFF) shl 16) or
                ((header[27].toInt() and 0xFF) shl 24)
            if (channels !in 1..8 || rate <= 0) return null
            return rate to channels
        }

        private fun u16(bytes: ByteArray, at: Int): Int =
            (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    }
}
