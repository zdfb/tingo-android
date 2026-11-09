package io.github.tancheng33.tingo.recording

import java.io.File
import java.io.RandomAccessFile

/**
 * 把一段 wav 截到指定长度。
 *
 * 静音自动结束录音时，桌面只保留到"最后有人说话之后 30 秒"——
 * 否则用户走开会、会议早散了，文件里还挂着几十分钟静音：转写慢、占空间、
 * 播放条拖到后面全是空白。
 *
 * 只处理我们自己的格式（16 bit PCM），头里的长度字段要一起改，否则播放器读到的还是原长。
 */
object WavTrim {

    /**
     * @param keepMs 保留到这一刻（毫秒）
     * @return 真的截短了返回 true；文件不存在、已经很短、格式不认识都返回 false
     */
    fun trimTo(file: File, keepMs: Long): Boolean {
        if (!file.isFile || keepMs <= 0) return false
        val header = WavWriter.HEADER_SIZE
        if (file.length() <= header) return false
        RandomAccessFile(file, "rw").use { out ->
            val head = ByteArray(header)
            out.readFully(head)
            val sampleRate = le32(head, 24)
            val channels = le16(head, 22)
            val bits = le16(head, 34)
            if (sampleRate <= 0 || channels <= 0 || bits != 16) return false
            val blockAlign = channels * bits / 8
            val keepBytes = (keepMs * sampleRate / 1000) * blockAlign
            val actual = out.length() - header
            if (keepBytes >= actual) return false
            out.setLength(header + keepBytes)
            // 头里的 RIFF 长度与 data 长度都要跟着改
            le32Into(head, 4, (36 + keepBytes).toInt())
            le32Into(head, 40, keepBytes.toInt())
            out.seek(0)
            out.write(head)
            return true
        }
    }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(bytes: ByteArray, offset: Int): Int =
        le16(bytes, offset) or (le16(bytes, offset + 2) shl 16)

    private fun le32Into(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
