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
                val resampler = AudioResample.StreamingResampler(sampleRate)
                WavFile.stream(target).use { writer ->
                    decodeChunks(codec, extractor) { chunk ->
                        val mono = AudioResample.toMono(chunk, channels)
                        writer.write(AudioResample.toPcm16(resampler.push(mono)))
                    }
                }
                return target
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    /**
     * 逐块解出交错的 16 bit 样本。
     *
     * 用回调而不是返回一个大数组：一小时的录音解码后是上百兆，整段放内存会 OOM。
     */
    private fun decodeChunks(
        codec: MediaCodec,
        extractor: MediaExtractor,
        onChunk: (ShortArray) -> Unit,
    ) {
        val info = MediaCodec.BufferInfo()
        var sawInputEnd = false
        var sawOutputEnd = false
        // 编解码器彻底不动（既不吃输入也不吐输出）时不能死等
        var idleRounds = 0
        while (!sawOutputEnd) {
            var progressed = false
            if (!sawInputEnd) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val buffer = codec.getInputBuffer(inputIndex)
                    if (buffer == null) {
                        // 输入缓冲拿不到是罕见情况；把这个下标还回去再试
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                    } else {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                    progressed = true
                }
            }
            val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outputIndex >= 0) {
                val buffer = codec.getOutputBuffer(outputIndex)
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val shorts = ShortArray(info.size / 2)
                    buffer.asShortBuffer().get(shorts)
                    onChunk(shorts)
                }
                codec.releaseOutputBuffer(outputIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                progressed = true
            }
            idleRounds = if (progressed) 0 else idleRounds + 1
            // 大约 3 秒毫无进展就当解码器卡住了，抛出去让用户看到"导入不了"
            if (idleRounds > MAX_IDLE_ROUNDS) throw IllegalStateException("解码器没有响应")
        }
    }

    private const val TIMEOUT_US = 10_000L

    /** 连续这么久没有任何输入/输出进展（约 3 秒）就判定解码器卡住。 */
    private const val MAX_IDLE_ROUNDS = 300

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
