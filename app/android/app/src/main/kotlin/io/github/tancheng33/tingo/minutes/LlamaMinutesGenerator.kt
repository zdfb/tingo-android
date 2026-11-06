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

    override suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): String {
        check(LlamaBridge.isAvailable) { "端侧语言模型不可用（该 ABI 没有内置库）" }
        val handle = LlamaBridge.load(modelFile.absolutePath, nThreads, nCtx)
        check(handle != 0L) { "语言模型加载失败" }
        try {
            val text = LlamaBridge.generate(handle, prompt, maxTokens)
            if (text.isNotEmpty()) onToken(text)
            return text
        } finally {
            LlamaBridge.free(handle)
        }
    }
}
