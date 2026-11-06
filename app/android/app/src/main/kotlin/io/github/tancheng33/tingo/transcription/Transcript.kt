package io.github.tancheng33.tingo.transcription

import io.github.tancheng33.tingo.files.writeAtomically
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 一句识别结果。字段与桌面版 `transcript.json` 的 `sentences[]` 一一对应。 */
data class TranscriptSentence(
    val speaker: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * 转写结果，与桌面版 v0.5.2 的 `transcript.json` 同构。
 *
 * 同构是硬要求：同一场会议要能在手机和电脑上互相打开。
 */
data class Transcript(
    val source: String,
    val durationMs: Long,
    val sentences: List<TranscriptSentence>,
    /** 说话人编号 → 名字；用户改过的名字在这里 */
    val speakerNames: Map<Int, String> = emptyMap(),
) {
    fun toJson(): String {
        val names = JSONObject()
        speakerNames.forEach { (speaker, name) -> names.put(speaker.toString(), name) }
        val array = JSONArray()
        sentences.forEach {
            array.put(
                JSONObject().apply {
                    put("speaker", it.speaker)
                    put("start_ms", it.startMs)
                    put("end_ms", it.endMs)
                    put("text", it.text)
                },
            )
        }
        return JSONObject().apply {
            put("source", source)
            put("duration_ms", durationMs)
            put("speaker_names", names)
            put("sentences", array)
        }.toString()
    }

    /** 按首次发言顺序排列的说话人编号。 */
    fun speakersInOrder(): List<Int> = sentences.map { it.speaker }.distinct()

    /** 说话人的显示名：改过就用改过的，没改过用"说话人 N"。 */
    fun speakerLabel(speaker: Int): String =
        speakerNames[speaker]?.takeIf { it.isNotBlank() } ?: "说话人${speaker + 1}"

    companion object {
        const val FILE_NAME = "transcript.json"

        /** 时间戳统一按 mm:ss 显示（出处用），别处不再各写一份。 */
        fun mmss(ms: Long): String {
            val totalSeconds = ms / 1000
            return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
        }

        fun fromJson(json: String): Transcript {
            val root = JSONObject(json)
            val names = mutableMapOf<Int, String>()
            val namesObj = root.optJSONObject("speaker_names") ?: JSONObject()
            namesObj.keys().forEach { key ->
                names[key.toInt()] = namesObj.getString(key)
            }
            val array = root.getJSONArray("sentences")
            val sentences = (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                TranscriptSentence(
                    speaker = o.getInt("speaker"),
                    startMs = o.getLong("start_ms"),
                    endMs = o.getLong("end_ms"),
                    text = o.getString("text"),
                )
            }
            return Transcript(
                source = root.optString("source"),
                durationMs = root.optLong("duration_ms"),
                sentences = sentences,
                speakerNames = names,
            )
        }
    }
}

/**
 * 把转写结果写进会议目录。
 *
 * 先写临时文件再改名：中途被杀不会留下半截 JSON——引擎那边会把半截文件判成"不可读"，
 * 那比"没有转写"更糟。
 */
object TranscriptWriter {

    fun write(dir: File, transcript: Transcript): File {
        val target = File(dir, Transcript.FILE_NAME)
        writeAtomically(target, transcript.toJson())
        return target
    }

    fun read(dir: File): Transcript? {
        val file = File(dir, Transcript.FILE_NAME)
        if (!file.isFile) return null
        return runCatching { Transcript.fromJson(file.readText()) }.getOrNull()
    }
}
