package io.github.tancheng33.tingo.data

import io.github.tancheng33.tingo.minutes.MinutesService
import io.github.tancheng33.tingo.transcription.Transcript
import io.github.tancheng33.tingo.transcription.TranscriptWriter
import java.io.File

/**
 * 导出一场会议为可以分享的 Markdown。
 *
 * 手机上"导出"最自然的落点就是分享面板：微信、邮件、备忘录都能接。
 * 文件名取会议标题并清掉路径不友好字符——标题来自纪要正文，可能含 `#`、`/` 之类。
 */
object MeetingExporter {

    /** @return 写好的文件；会议既没纪要也没转写时返回 null */
    fun export(meetingDir: File, meetingTitle: String, into: File): File? {
        val minutes = File(meetingDir, MinutesService.FILE_NAME).takeIf { it.isFile }?.readText()
        val transcript = TranscriptWriter.read(meetingDir)
        val body = compose(minutes, transcript) ?: return null

        into.mkdirs()
        val file = File(into, fileStem(meetingTitle) + ".md")
        file.writeText(body)
        return file
    }

    /** 有纪要就只导纪要；没有纪要但有转写，就把转写排成可读的 Markdown。 */
    fun compose(minutes: String?, transcript: Transcript?): String? {
        val trimmedMinutes = minutes?.trim().orEmpty()
        if (trimmedMinutes.isNotEmpty()) {
            return if (trimmedMinutes.endsWith("\n")) trimmedMinutes else "$trimmedMinutes\n"
        }
        if (transcript == null || transcript.sentences.isEmpty()) return null
        return buildString {
            append("# 会议转写\n\n")
            transcript.sentences.forEach { sentence ->
                append("- **").append(transcript.speakerLabel(sentence.speaker)).append("**（")
                append(Transcript.mmss(sentence.startMs)).append("）：")
                append(sentence.text.trim()).append('\n')
            }
        }
    }

    /** 文件名：清掉路径分隔符与控制字符，过长截断。空标题给一个兜底名。 */
    fun fileStem(title: String): String {
        val cleaned = title
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
        val safe = cleaned.ifEmpty { "会议纪要" }
        return if (safe.length <= MAX_STEM) safe else safe.take(MAX_STEM).trim()
    }

    private const val MAX_STEM = 60
}
