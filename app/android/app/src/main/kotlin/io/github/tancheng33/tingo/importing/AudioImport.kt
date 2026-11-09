package io.github.tancheng33.tingo.importing

import io.github.tancheng33.tingo.engine.TingoEnginePort
import io.github.tancheng33.tingo.transcription.Transcriber
import java.io.File

/** 解码这一步的端口：真实现是 [AudioDecoder]（要 MediaCodec，只能在真机跑）。 */
interface AudioDecoding {
    fun toWav(source: File, target: File): File
}

/** 系统解码器的端口实现。 */
object SystemAudioDecoding : AudioDecoding {
    override fun toWav(source: File, target: File): File = AudioDecoder.toWav(source, target)
}

/**
 * 导入一段已有的录音：解码 → 落成一场会议 → 转写。
 *
 * 桌面端是同一条链（上传文件、引擎写进会议文件夹、后台转写），
 * 差别只在于解码器由谁提供：桌面用 macOS 的 `afconvert`，手机用系统的 `MediaCodec`。
 */
class AudioImport(
    private val engine: TingoEnginePort,
    private val transcriber: Transcriber,
    private val cacheDir: File,
    private val decoding: AudioDecoding = SystemAudioDecoding,
) {

    /**
     * @param source 用户选中的原始文件（可能在任何位置）
     * @param title 会议标题；空则用文件名
     * @param modifiedMs 文件修改时间，用来决定会议时间；0 表示用现在
     * @param onProgress 0..1：解码占前 15%，转写占其余
     * @return 新会议 id
     */
    suspend fun run(
        source: File,
        title: String,
        modifiedMs: Long,
        onProgress: (Float) -> Unit = {},
    ): String {
        val wav = File(cacheDir, "import-${System.nanoTime()}.wav")
        try {
            onProgress(0f)
            decoding.toWav(source, wav)
            onProgress(DECODE_SHARE)
            val name = title.ifBlank { AudioDecoder.titleOf(source.name) }
            // 先落成会议：引擎按同一套规则起名、建目录、写 `录音.wav`
            val id = engine.importAudio(wav.absolutePath, name, modifiedMs)
            val dir = File(File(engine.meetingsRoot(), "meetings"), id)
            transcriber.transcribe(wav, dir) { fraction ->
                onProgress(DECODE_SHARE + fraction * (1f - DECODE_SHARE))
            }
            onProgress(1f)
            return id
        } finally {
            // 解码出来的 wav 只是中转：会议目录里已经有一份，留着白占空间
            wav.delete()
        }
    }

    private companion object {
        /** 解码在总进度里占的比重：大文件解码与转写都在跑，先给个小小的一段。 */
        const val DECODE_SHARE = 0.15f
    }
}
