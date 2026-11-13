package io.github.tancheng33.tingo.recording

import java.io.File
import org.json.JSONArray

object RecordingHighlights {
    private const val FILE_NAME = "highlights.json"

    fun read(meetingDir: File): List<Long> {
        val file = File(meetingDir, FILE_NAME)
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { array.getLong(it) }
        }.getOrDefault(emptyList())
    }

    fun write(meetingDir: File, highlights: List<Long>) {
        val file = File(meetingDir, FILE_NAME)
        val temporary = File(meetingDir, "$FILE_NAME.tmp")
        temporary.writeText(JSONArray(highlights).toString())
        check(temporary.renameTo(file)) { "Unable to save recording highlights" }
    }
}
