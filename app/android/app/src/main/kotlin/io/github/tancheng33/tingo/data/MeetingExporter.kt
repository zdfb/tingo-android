package io.github.tancheng33.tingo.data

import io.github.tancheng33.tingo.minutes.MinutesService
import io.github.tancheng33.tingo.transcription.Transcript
import io.github.tancheng33.tingo.transcription.TranscriptWriter
import io.github.tancheng33.tingo.recording.RecordingTracks
import io.github.tancheng33.tingo.recording.TrackMerger
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 导出会议：要么一份可读的 Markdown（分享用），要么一个完整归档 zip（存档用）。
 *
 * 手机上"导出"有两个真实需求，不能混成一个：
 * - 发给同事看 → 一段文字就够，走分享面板；
 * - 留档/搬机器 → 录音、转写、纪要要一起带走，走 zip。
 *
 * 文件名取会议标题并清掉路径不友好字符——标题来自纪要正文，可能含 `#`、`/` 之类。
 */
object MeetingExporter {

    /** @return 写好的文件；会议既没纪要也没转写时返回 null */
    fun export(
        meetingDir: File,
        meetingTitle: String,
        into: File,
        transcriptTitle: String = "会议转写",
        emptyTitle: String = "会议纪要",
    ): File? {
        val minutes = File(meetingDir, MinutesService.FILE_NAME).takeIf { it.isFile }?.readText()
        val transcript = TranscriptWriter.read(meetingDir)
        val body = compose(minutes, transcript, transcriptTitle) ?: return null

        into.mkdirs()
        val file = File(into, fileStem(meetingTitle, emptyTitle) + ".md")
        file.writeText(body)
        return file
    }

    /**
     * 打一个归档 zip：录音 + transcript.json + 纪要.md（有什么打什么）。
     *
     * @return 生成的 zip；会议里没有任何可导出的内容时返回 null
     */
    fun exportBundle(
        meetingDir: File,
        meetingTitle: String,
        into: File,
        emptyTitle: String = "会议纪要",
    ): File? {
        val entries = bundleEntries(meetingDir)
        if (entries.isEmpty()) return null

        into.mkdirs()
        val zip = File(into, fileStem(meetingTitle, emptyTitle) + ".zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            entries.forEach { (name, file) ->
                out.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return zip
    }

    /**
     * zip 里装哪些文件：只要真实存在且非空的。
     *
     * 音频不写死 `录音.wav`：崩溃恢复出来的会议只有 `mic.wav`/`system.wav`，
     * 写死会让归档悄悄丢掉录音。
     */
    fun bundleEntries(meetingDir: File): List<Pair<String, File>> {
        // 音轨可能不止一路：崩溃恢复的会议留着 mic.wav 与 system.wav，两路都要带上，
        // 否则归档里只剩本机一侧的声音
        val audio = RecordingTracks.audioTracks(meetingDir).map { it.name to it }
        val others = BUNDLE_NAMES
            .filter { it != RECORDING_NAME }
            .map { name -> name to File(meetingDir, name) }
            .filter { (_, file) -> file.isFile && file.length() > 0 }
        return audio + others
    }

    /** 有纪要就只导纪要；没有纪要但有转写，就把转写排成可读的 Markdown。 */
    fun compose(minutes: String?, transcript: Transcript?, transcriptTitle: String = "会议转写"): String? {
        val trimmedMinutes = minutes?.trim().orEmpty()
        if (trimmedMinutes.isNotEmpty()) {
            return if (trimmedMinutes.endsWith("\n")) trimmedMinutes else "$trimmedMinutes\n"
        }
        if (transcript == null || transcript.sentences.isEmpty()) return null
        return buildString {
            append("# ").append(transcriptTitle).append("\n\n")
            transcript.sentences.forEach { sentence ->
                append("- **").append(transcript.speakerLabel(sentence.speaker)).append("**（")
                append(Transcript.mmss(sentence.startMs)).append("）：")
                append(sentence.text.trim()).append('\n')
            }
        }
    }

    /** 文件名：清掉路径分隔符与控制字符，过长截断。空标题给一个兜底名。 */
    fun fileStem(title: String, emptyTitle: String = "会议纪要"): String {
        val cleaned = title
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
        val safe = cleaned.ifEmpty { emptyTitle }
        return if (safe.length <= MAX_STEM) safe else safe.take(MAX_STEM).trim()
    }

    private const val MAX_STEM = 60

    /** 音频文件名由 [TrackMerger] 定义，这里不重复写死字面量。 */
    private val RECORDING_NAME get() = TrackMerger.MIXED

    /** 归档里带走的非音频文件：与桌面版一场会议的产物一一对应。 */
    private val BUNDLE_NAMES = listOf(
        "transcript.json",
        MinutesService.FILE_NAME,
    )
}
