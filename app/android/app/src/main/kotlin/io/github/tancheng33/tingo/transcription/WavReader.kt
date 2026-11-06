package io.github.tancheng33.tingo.transcription

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/** 读出来的单声道音频：样本已归一化到 -1..1，采样率照抄文件头。 */
data class Pcm(val samples: FloatArray, val sampleRate: Int) {
    val durationMs: Long
        get() = if (sampleRate == 0) 0 else samples.size * 1000L / sampleRate
}

/**
 * 只读我们自己的录音格式：16 bit PCM 的 wav。
 *
 * 不做通用解码（mp3/m4a 交给别的路径），因为手机录出来的就是 wav；
 * 桌面版录的 m4a 在手机上也不需要重新转写。
 */
object WavReader {

    /** wav 头长度；短于这个数的文件不可能有音频 */
    const val HEADER_BYTES = 44L

    /** wav 的格式信息（不读音频数据）。 */
    data class Format(val sampleRate: Int, val dataBytes: Long) {
        val durationMs: Long
            get() = if (sampleRate == 0) 0 else dataBytes / 2 * 1000 / sampleRate
    }

    /**
     * 只读文件头。
     *
     * 长会议录音动辄几百兆，任何"先读进内存再处理"的写法都会在真机上 OOM；
     * 转写走 [stream]，这个方法只用来拿采样率与时长。
     */
    fun format(file: File): Format {
        RandomAccessFile(file, "r").use { raf ->
            val header = readHeader(raf, file.name)
            val sampleRate = u32(header, 24)
            // 头部长度字段在"边录边写"时不可信，按文件实际大小算
            return Format(sampleRate, max(0L, file.length() - HEADER_BYTES))
        }
    }

    /** 解析并校验 44 字节头：只支持 16 bit 单声道 PCM，和我们的录音格式一致。 */
    private fun readHeader(raf: RandomAccessFile, name: String, allowStereo: Boolean = false): ByteArray {
        val header = ByteArray(HEADER_BYTES.toInt())
        if (raf.read(header) != header.size) error("不是有效的 wav：$name")
        require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF") { "不是 RIFF 文件" }
        require(String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "不是 WAVE 文件" }
        val channels = u16(header, 22)
        val bits = u16(header, 34)
        require(bits == 16) { "只支持 16 bit PCM，实际 $bits" }
        require(allowStereo || channels == 1) { "只支持单声道，实际 $channels" }
        return header
    }

    /**
     * 分块读出音频样本，内存里同时只有 [chunkSamples] 个样本。
     *
     * @param onChunk 每块的样本与它在整段音频里的起始样本下标
     */
    fun stream(file: File, chunkSamples: Int = 16_000, onChunk: (FloatArray, Long) -> Unit) {
        val sampleRate = format(file).sampleRate
        val chunkBytes = chunkSamples * 2
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(HEADER_BYTES)
            val buffer = ByteArray(chunkBytes)
            var startSample = 0L
            while (true) {
                val read = raf.read(buffer)
                if (read <= 0) break
                val samples = FloatArray(read / 2)
                val byteBuffer = ByteBuffer.wrap(buffer, 0, read).order(ByteOrder.LITTLE_ENDIAN)
                for (i in samples.indices) samples[i] = byteBuffer.short / 32_768f
                onChunk(samples, startSample)
                startSample += samples.size
            }
        }
    }

    /**
     * 宽容读取：立体声会降到单声道。
     *
     * 系统内录拿到的是 48 kHz 立体声，而我们自己录的是 16 kHz 单声道；
     * 后者用严格的 [read]，前者用这个。
     */
    fun readAnyChannels(file: File): Pcm = read(file, allowStereo = true)

    fun read(file: File, allowStereo: Boolean = false): Pcm {
        RandomAccessFile(file, "r").use { raf ->
            val header = readHeader(raf, file.name, allowStereo)
            val sampleRate = u32(header, 24)
            val channels = u16(header, 22)
            val dataBytes = max(0L, (file.length() - HEADER_BYTES)).toInt()
            val raw = ByteArray(dataBytes)
            var read = 0
            while (read < dataBytes) {
                val n = raf.read(raw, read, dataBytes - read)
                if (n <= 0) break
                read += n
            }
            val buffer = ByteBuffer.wrap(raw, 0, read).order(ByteOrder.LITTLE_ENDIAN)
            val interleaved = FloatArray(read / 2) { buffer.short / 32_768f }
            val samples = if (channels == 1) interleaved else {
                FloatArray(interleaved.size / channels) { frame ->
                    var sum = 0f
                    for (channel in 0 until channels) sum += interleaved[frame * channels + channel]
                    sum / channels
                }
            }
            return Pcm(samples, sampleRate)
        }
    }

    private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)
}
