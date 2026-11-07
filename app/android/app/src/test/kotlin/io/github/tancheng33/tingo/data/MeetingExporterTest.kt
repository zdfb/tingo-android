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

/** 归档导出：留下的是"能搬走的整场会议"，不是一个摘要。 */
class MeetingExporterBundleTest {

    private fun dir(): File = Files.createTempDirectory("tingo-bundle").toFile()

    private fun meeting(withAudio: Boolean = true, withTranscript: Boolean = true, withMinutes: Boolean = true): File {
        val dir = dir()
        File(dir, "会议").let { }
        if (withAudio) {
            File(dir, "录音.wav").writeBytes(ByteArray(100) { 1 })
        }
        if (withTranscript) {
            File(dir, "transcript.json").writeText("""{"source":"录音.wav","durationMs":1000,"sentences":[]}""")
        }
        if (withMinutes) {
            File(dir, "纪要.md").writeText("# 一句话总结\n\n测试\n")
        }
        return dir
    }

    private fun namesIn(zip: File): Set<String> {
        java.util.zip.ZipFile(zip).use { file ->
            return file.entries().toList().map { it.name }.toSet()
        }
    }

    @Test
    fun bundleContainsAudioTranscriptAndMinutes() {
        val meeting = meeting()
        val zip = MeetingExporter.exportBundle(meeting, "周会", dir())!!

        assertEquals(setOf("录音.wav", "transcript.json", "纪要.md"), namesIn(zip))
    }

    @Test
    fun bundleSkipsMissingPieces() {
        val meeting = meeting(withAudio = false, withMinutes = false)
        val zip = MeetingExporter.exportBundle(meeting, "周会", dir())!!

        assertEquals(setOf("transcript.json"), namesIn(zip))
    }

    @Test
    fun emptyMeetingProducesNothing() {
        val meeting = meeting(withAudio = false, withTranscript = false, withMinutes = false)

        assertEquals(null, MeetingExporter.exportBundle(meeting, "周会", dir()))
        assertTrue(MeetingExporter.bundleEntries(meeting).isEmpty())
    }

    @Test
    fun zipContentRoundTrips() {
        val meeting = meeting()
        val zip = MeetingExporter.exportBundle(meeting, "周会", dir())!!

        java.util.zip.ZipFile(zip).use { file ->
            val entry = file.getEntry("纪要.md")
            val text = file.getInputStream(entry).bufferedReader().readText()
            assertEquals("# 一句话总结\n\n测试\n", text)
        }
    }

    @Test
    fun fileStemIsUsedForTheZipName() {
        val zip = MeetingExporter.exportBundle(meeting(), "a/b:c", dir())!!

        assertTrue("非法字符应被清掉：${zip.name}", zip.name == "a b c.zip")
    }
}
