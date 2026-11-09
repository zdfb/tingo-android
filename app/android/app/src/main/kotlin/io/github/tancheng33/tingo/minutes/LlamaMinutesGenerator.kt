package io.github.tancheng33.tingo.minutes

import java.io.File

/**
 * 端侧语言模型的纪要生成器。
 *
 * 每次生成都重新加载模型然后释放：模型常驻会跟语音模型抢内存，
 * 而纪要是一次性的活儿，加载 0.6B 只要百毫秒级。
 */
class LlamaMinutesGenerator(
    private val modelFile: File,
    private val nThreads: Int = 4,
    private val nCtx: Int = 4_096,
) : MinutesGenerator {

    private companion object {
        /** 给模型输出留的余量：至少要能写完五个章节，低于这个数不如直接报失败 */
        const val OUTPUT_RESERVE_TOKENS = 256
    }

    override suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): String {
        check(LlamaBridge.isAvailable) { "端侧语言模型不可用（该 ABI 没有内置库）" }
        val handle = LlamaBridge.load(modelFile.absolutePath, nThreads, nCtx)
        check(handle != 0L) { "语言模型加载失败" }
        try {
            // 提示词 + 生成长度必须一起装进上下文：装不下时生成会失败（返回空串），
            // 用户看到的是"生成失败"却不知道原因。所以先裁提示词，再按剩余空间收窄生成长度。
            val budget = nCtx - maxTokens - OUTPUT_RESERVE_TOKENS
            val fitted = MinutesPrompt.trimToTokenBudget(prompt, budget) { LlamaBridge.countTokens(handle, it) }
            val text = LlamaBridge.generate(handle, fitted, maxTokens)
            if (text.isNotEmpty()) onToken(text)
            return text
        } finally {
            LlamaBridge.free(handle)
        }
    }
}
