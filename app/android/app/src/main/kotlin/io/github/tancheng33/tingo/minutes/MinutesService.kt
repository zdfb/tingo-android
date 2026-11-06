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
    suspend fun generate(transcript: Transcript, meetingDir: File, onToken: (String) -> Unit = {}): File {
        require(transcript.sentences.isNotEmpty()) { "没有转写内容，无法生成纪要" }

        val prompt = MinutesPrompt.build(transcript)
        var markdown = generator.generate(prompt, maxTokens, onToken)
        var problems = MinutesPrompt.validate(markdown)

        if (problems.isNotEmpty()) {
            markdown = generator.generate(MinutesPrompt.retryPrompt(prompt, problems), maxTokens, onToken)
            problems = MinutesPrompt.validate(markdown)
        }
        if (problems.isNotEmpty()) {
            // 规格要求"保留原文供手动编辑"：不写坏的 纪要.md，但把模型输出留在草稿里
            val draft = runCatching { write(meetingDir, markdown, DRAFT_FILE_NAME) }.getOrNull()
            throw MinutesRejected(problems, draft)
        }

        return write(meetingDir, markdown)
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
