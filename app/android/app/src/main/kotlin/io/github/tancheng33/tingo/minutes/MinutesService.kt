package io.github.tancheng33.tingo.minutes

import io.github.tancheng33.tingo.files.writeAtomically
import io.github.tancheng33.tingo.transcription.Transcript
import java.io.File

/** 生成一段纪要文本；实现里可能是端侧模型，也可能是测试替身。 */
interface MinutesGenerator {
    suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit = {}): String
}

/**
 * 纪要实现：拼提示词 → 生成 → 校验 → 不合格重试一次 → 写 `纪要.md`。
 *
 * 重试只做一次：0.6B 的模型偶尔会自己漏章节，第二次带着问题清单通常能补上；
 * 第三次再不行就说明这次转写它确实写不动，硬试只是浪费手机电量。
 */
class MinutesService(
    private val generator: MinutesGenerator,
    /**
     * 提示词里的"专有名词"段；由调用方从引擎按**纪要语言**取（与桌面同一份文案）。
     *
     * 参数是 "zh"/"en"：英文纪要里塞一段中文句子，模型会跟着改口。
     */
    private val glossary: (language: String) -> String = { "" },
    /** 纪要语言设置：`auto | zh | en`（桌面 `[minutes] language` 同一套取值）。 */
    private val languageSetting: () -> String = { MinutesLanguage.AUTO },
    private val maxTokens: Int = 1_200,
) {
    /** @param draft 自检不通过时保留的草稿文件（可能为 null：连草稿都写不下去） */
    class MinutesRejected(
        val problems: List<String>,
        val draft: File? = null,
    ) : Exception(problems.joinToString("；"))

    /**
     * @return 写好的 `纪要.md`
     */
    suspend fun generate(
        transcript: Transcript,
        meetingDir: File,
        onToken: (String) -> Unit = {},
        /**
         * 最终纪要怎么写。
         *
         * 默认直接覆盖文件；Android 侧传的是"经引擎保存"——引擎会先把上一版归档进
         * `minutes-history`。重新生成必须走这条路，否则再生成一次就把之前那版悄悄抹掉了，
         * 而历史版本存在的意义就是能退回去。
         */
        saveFinal: (String) -> Unit = { write(meetingDir, it) },
    ): File {
        require(transcript.sentences.isNotEmpty()) { "没有转写内容，无法生成纪要" }

        val spoken = MinutesLanguage.detect(transcript.sentences.map { it.text })
        val language = MinutesLanguage.resolve(languageSetting(), transcript.sentences.map { it.text })
        val prompt = MinutesPrompt.build(
            transcript,
            glossary = glossary(language),
            language = language,
            meetingDate = meetingDate(meetingDir),
            // 选定的纪要语言与会议说的话不一致时，明确要求翻译
            spokenLanguage = spoken,
        )
        var markdown = generator.generate(prompt, maxTokens, onToken)
        var problems = MinutesPrompt.validate(markdown, language)

        if (problems.isNotEmpty()) {
            markdown = generator.generate(
                MinutesPrompt.retryPrompt(prompt, problems, language),
                maxTokens,
                onToken,
            )
            problems = MinutesPrompt.validate(markdown, language)
        }
        if (problems.isNotEmpty()) {
            // 规格要求"保留原文供手动编辑"：不写坏的 纪要.md，但把模型输出留在草稿里
            val draft = runCatching { write(meetingDir, markdown, DRAFT_FILE_NAME) }.getOrNull()
            throw MinutesRejected(problems, draft)
        }

        saveFinal(markdown)
        return File(meetingDir, FILE_NAME)
    }

    /**
     * 会议日期，取自会议目录名（`2026-10-28_1430_复盘`）。
     *
     * 提示词要它才能把"下周五"换算成具体日子；目录名不像会议名时返回 null，
     * 模板里就留空——比编一个日期好。
     */
    private fun meetingDate(meetingDir: File): String? {
        val name = meetingDir.name
        val date = runCatching { java.time.LocalDate.parse(name.take(10)) }.getOrNull() ?: return null
        val weekdayZh = listOf("一", "二", "三", "四", "五", "六", "日")[date.dayOfWeek.value - 1]
        return "${date}（星期$weekdayZh） / ${date} (${date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)})"
    }

    /** 先写临时文件再改名：被系统杀掉时不会留下半截纪要。 */
    fun write(meetingDir: File, markdown: String, fileName: String = FILE_NAME): File {
        val target = File(meetingDir, fileName)
        writeAtomically(target, markdown.trimEnd() + "\n")
        return target
    }

    companion object {
        const val FILE_NAME = "纪要.md"

        /** 自检不通过时模型输出的落点，供用户手动改完再另存 */
        const val DRAFT_FILE_NAME = "纪要-草稿.md"
    }
}
