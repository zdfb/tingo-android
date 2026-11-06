package io.github.tancheng33.tingo.data

import io.github.tancheng33.tingo.minutes.MinutesService
import io.github.tancheng33.tingo.transcription.Transcript
import io.github.tancheng33.tingo.transcription.TranscriptSentence
import io.github.tancheng33.tingo.transcription.TranscriptWriter
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingExporterTest {

    private fun dir(): File = Files.createTempDirectory("tingo-export").toFile()

    private val transcript = Transcript(
        source = "录音.wav",
        durationMs = 65_000,
        sentences = listOf(
            TranscriptSentence(0, 0, 3_000, "今天复盘注册转化。"),
            TranscriptSentence(1, 61_000, 65_000, "那先修首屏。"),
        ),
        speakerNames = mapOf(0 to "林舟"),
    )

    @Test
    fun minutesAreExportedVerbatim() {
        val meeting = dir()
        File(meeting, MinutesService.FILE_NAME).writeText("# 复盘\n\n## 决议\n- 先修首屏\n")

        val file = MeetingExporter.export(meeting, "官网注册转化复盘", dir())!!

        assertEquals("官网注册转化复盘.md", file.name)
        assertEquals("# 复盘\n\n## 决议\n- 先修首屏\n", file.readText())
    }

    @Test
    fun withoutMinutesTheTranscriptIsFormatted() {
        val meeting = dir()
        TranscriptWriter.write(meeting, transcript)

        val file = MeetingExporter.export(meeting, "只有转写", dir())!!

        val text = file.readText()
        assertTrue(text.startsWith("# 会议转写"))
        assertTrue(text.contains("- **林舟**（00:00）：今天复盘注册转化。"))
        assertTrue(text.contains("- **说话人2**（01:01）：那先修首屏。"))
    }

    @Test
    fun minutesWinOverTranscript() {
        val composed = MeetingExporter.compose("# 纪要正文", transcript)
        assertEquals("# 纪要正文\n", composed)
    }

    @Test
    fun emptyMeetingExportsNothing() {
        assertNull(MeetingExporter.export(dir(), "空会议", dir()))
        assertNull(MeetingExporter.compose("   ", transcript.copy(sentences = emptyList())))
    }

    @Test
    fun fileStemStripsPathUnfriendlyCharacters() {
        assertEquals("官网 注册 转化", MeetingExporter.fileStem("官网/注册:转化"))
        assertEquals("会议纪要", MeetingExporter.fileStem("   "))
        assertEquals("会议纪要", MeetingExporter.fileStem("..."))
        assertTrue(MeetingExporter.fileStem("长".repeat(200)).length <= 60)
    }

    @Test
    fun exportCreatesDirectoryWhenMissing() {
        val meeting = dir()
        File(meeting, MinutesService.FILE_NAME).writeText("# 复盘\n")
        val target = File(dir(), "nested/deeper")

        val file = MeetingExporter.export(meeting, "复盘", target)!!

        assertTrue(file.isFile)
        assertEquals(target.absolutePath, file.parentFile!!.absolutePath)
    }
}
