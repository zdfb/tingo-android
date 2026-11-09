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
        target.parentFile?.mkdirs()
        val dataBytes = samples.size * 2
        target.outputStream().buffered().use { out ->
            out.write(header(dataBytes, sampleRate))
            val buffer = ByteArray(dataBytes)
            samples.forEachIndexed { index, sample ->
                val value = sample.toInt()
                buffer[index * 2] = (value and 0xFF).toByte()
                buffer[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            }
            out.write(buffer)
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
