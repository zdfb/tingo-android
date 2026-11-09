package io.github.tancheng33.tingo.importing

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/**
 * 把用户选中的任意音频文件解码成「16 kHz 单声道 wav」。
 *
 * 桌面端把原文件交给引擎、由 macOS 的 `afconvert` 解码；手机上引擎不带解码器，
 * 所以用系统的 `MediaExtractor` + `MediaCodec` 解，再用 [AudioResample] 降到 16k。
 *
 * 只解音频轨、只保留样本：一小时的会议整段浮点样本约 230 MB，所以边解边写盘。
 */
object AudioDecoder {

    /**
     * @return 写好的 16k 单声道 wav
     * @throws IllegalArgumentException 文件里没有音频轨，或系统没有对应解码器
     */
    fun toWav(source: File, target: File): File {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("这个文件里没有音频")

            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("读不出音频格式")
            extractor.selectTrack(track)

            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val samples = decodeAll(codec, extractor)
                val mono = AudioResample.resample(
                    samples = AudioResample.toMono(samples, channels),
                    fromRate = sampleRate,
                )
                WavFile.write(target, AudioResample.toPcm16(mono))
                return target
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    /** 把所有解码片段拼成一个交错的 16 bit 样本数组（内存里只留样本）。 */
    private fun decodeAll(codec: MediaCodec, extractor: MediaExtractor): ShortArray {
        val info = MediaCodec.BufferInfo()
        val chunks = ArrayList<ShortArray>()
        var total = 0
        var sawInputEnd = false
        var sawOutputEnd = false
        while (!sawOutputEnd) {
            if (!sawInputEnd) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val buffer = codec.getInputBuffer(inputIndex) ?: continue
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEnd = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> {
                    if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val shorts = ShortArray(info.size / 2)
                            buffer.asShortBuffer().get(shorts)
                            chunks += shorts
                            total += shorts.size
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                    }
                }
            }
        }
        val out = ShortArray(total)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }

    private const val TIMEOUT_US = 10_000L

    /** 给调用方一个明确的"支持哪些格式"（与桌面 `AUDIO_EXTS` 一致）。 */
    val SUPPORTED: Set<String> = setOf(
        "m4a", "mp3", "wav", "mp4", "aac", "flac", "ogg", "webm", "mov", "mkv",
    )

    /** 文件扩展名是否在支持列表里。 */
    fun supports(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in SUPPORTED

    /** 用来给会议起名的默认标题：文件名去掉扩展名。 */
    fun titleOf(name: String): String = name.substringBeforeLast('.').ifBlank { "导入录音" }
}
