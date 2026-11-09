package io.github.tancheng33.tingo.importing

import java.io.File

/**
 * 写 16 bit PCM 单声道 wav。
 *
 * 引擎里的录音就是这个格式（`WavReader` 只认它），所以导入素材必须先转成这一种，
 * 后面播放、转写、导出才不用各写一套判断。
 */
object WavFile {

    private const val HEADER_BYTES = 44
    private const val CHANNELS = 1
    private const val BITS = 16

    fun write(target: File, samples: ShortArray, sampleRate: Int = AudioResample.TARGET_RATE) {
        stream(target, sampleRate).use { writer -> writer.write(samples) }
    }

    /** 边解边写：一小时的录音整段留在内存里会 OOM，所以只按块写。 */
    fun stream(target: File, sampleRate: Int = AudioResample.TARGET_RATE): Writer = Writer(target, sampleRate)

    /** 先写占位头，写完再回填长度（wav 的长度字段在文件头里）。 */
    class Writer internal constructor(target: File, private val sampleRate: Int) : AutoCloseable {
        private val file = java.io.RandomAccessFile(target.apply { parentFile?.mkdirs() }, "rw")
        private var dataBytes = 0L

        init {
            file.write(header(0, sampleRate))
        }

        fun write(samples: ShortArray) {
            val buffer = ByteArray(samples.size * 2)
            samples.forEachIndexed { index, sample ->
                val value = sample.toInt()
                buffer[index * 2] = (value and 0xFF).toByte()
                buffer[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            }
            file.write(buffer)
            dataBytes += buffer.size
        }

        override fun close() {
            if (dataBytes <= Int.MAX_VALUE) file.seek(0) .also { file.write(header(dataBytes.toInt(), sampleRate)) }
            file.close()
        }
    }

    /** RIFF 头；单测直接验它，避免"写出来的文件播放器打不开"。 */
    fun header(dataBytes: Int, sampleRate: Int): ByteArray {
        val byteRate = sampleRate * CHANNELS * BITS / 8
        val blockAlign = CHANNELS * BITS / 8
        val out = ByteArray(HEADER_BYTES)
        var offset = 0
        fun ascii(text: String) {
            text.forEach { out[offset++] = it.code.toByte() }
        }
        fun le32(value: Int) {
            for (shift in 0..24 step 8) out[offset++] = ((value shr shift) and 0xFF).toByte()
        }
        fun le16(value: Int) {
            out[offset++] = (value and 0xFF).toByte()
            out[offset++] = ((value shr 8) and 0xFF).toByte()
        }
        ascii("RIFF")
        le32(36 + dataBytes)
        ascii("WAVE")
        ascii("fmt ")
        le32(16)
        le16(1) // PCM
        le16(CHANNELS)
        le32(sampleRate)
        le32(byteRate)
        le16(blockAlign)
        le16(BITS)
        ascii("data")
        le32(dataBytes)
        require(offset == HEADER_BYTES) { "头部长度不对：$offset" }
        return out
    }
}
