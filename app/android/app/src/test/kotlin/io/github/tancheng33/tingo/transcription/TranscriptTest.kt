package io.github.tancheng33.tingo.transcription

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {

    private fun dir(): File = Files.createTempDirectory("tingo-transcript").toFile()

    private val sample = Transcript(
        source = "录音.wav",
        durationMs = 120_000,
        sentences = listOf(
            TranscriptSentence(speaker = 0, startMs = 0, endMs = 2_500, text = "我们先看注册页。"),
            TranscriptSentence(speaker = 1, startMs = 2_500, endMs = 5_000, text = "首屏要 4 秒。"),
            TranscriptSentence(speaker = 0, startMs = 5_000, endMs = 7_000, text = "那就先修首屏。"),
        ),
        speakerNames = mapOf(0 to "林舟"),
    )

    @Test
    fun jsonMatchesDesktopSchema() {
        val json = JSONObject(sample.toJson())

        assertEquals("录音.wav", json.getString("source"))
        assertEquals(120_000L, json.getLong("duration_ms"))
        // 桌面版用字符串键："0" 而不是 0
        assertEquals("林舟", json.getJSONObject("speaker_names").getString("0"))

        val sentences = json.getJSONArray("sentences")
        assertEquals(3, sentences.length())
        val first = sentences.getJSONObject(0)
        assertEquals(0, first.getInt("speaker"))
        assertEquals(0L, first.getLong("start_ms"))
        assertEquals(2_500L, first.getLong("end_ms"))
        assertEquals("我们先看注册页。", first.getString("text"))
    }

    @Test
    fun roundTripsThroughJson() {
        val back = Transcript.fromJson(sample.toJson())

        assertEquals(sample.source, back.source)
        assertEquals(sample.durationMs, back.durationMs)
        assertEquals(sample.sentences, back.sentences)
        assertEquals(sample.speakerNames, back.speakerNames)
    }

    @Test
    fun speakersComeBackInFirstSpokenOrder() {
        val transcript = sample.copy(
            sentences = listOf(
                TranscriptSentence(2, 0, 1_000, "b"),
                TranscriptSentence(0, 1_000, 2_000, "a"),
                TranscriptSentence(2, 2_000, 3_000, "b again"),
            ),
        )
        assertEquals(listOf(2, 0), transcript.speakersInOrder())
    }

    @Test
    fun writerIsAtomicAndReadable() {
        val meeting = dir()
        val written = TranscriptWriter.write(meeting, sample)

        assertTrue(written.isFile)
        assertFalse(File(meeting, "transcript.json.tmp").exists())
        assertEquals(sample.sentences, TranscriptWriter.read(meeting)!!.sentences)
    }

    @Test
    fun writerReplacesPreviousContent() {
        val meeting = dir()
        TranscriptWriter.write(meeting, sample)
        TranscriptWriter.write(meeting, sample.copy(sentences = emptyList()))

        assertEquals(0, TranscriptWriter.read(meeting)!!.sentences.size)
    }

    @Test
    fun missingOrBrokenFileReadsAsNull() {
        val meeting = dir()
        assertNull(TranscriptWriter.read(meeting))

        File(meeting, Transcript.FILE_NAME).writeText("{not json")
        assertNull(TranscriptWriter.read(meeting))
    }
}
