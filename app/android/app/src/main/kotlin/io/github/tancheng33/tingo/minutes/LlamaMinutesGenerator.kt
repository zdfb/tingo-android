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
        /** 提示词最少要留下的 token 数（标题 + 指令 + 一点转写），再少就没有生成的意义 */
        const val MIN_PROMPT_TOKENS = 512
        /** 生成长度的下限：章节结构本身就吃不少 */
        const val MIN_OUTPUT_TOKENS = 256
    }

    override suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): String {
        check(LlamaBridge.isAvailable) { "端侧语言模型不可用（该 ABI 没有内置库）" }
        val handle = LlamaBridge.load(modelFile.absolutePath, nThreads, nCtx)
        check(handle != 0L) { "语言模型加载失败" }
        try {
            // 提示词 + 生成长度必须一起装进上下文：装不下时生成会失败（返回空串），
            // 用户看到的是"生成失败"却不知道原因。所以先按上下文收窄生成长度，再裁提示词。
            val outputTokens = maxTokens.coerceIn(
                MIN_OUTPUT_TOKENS,
                nCtx - MIN_PROMPT_TOKENS - OUTPUT_RESERVE_TOKENS,
            )
            val budget = nCtx - outputTokens - OUTPUT_RESERVE_TOKENS
            val trimmed = MinutesPrompt.trimToTokenBudget(prompt, budget) { LlamaBridge.countTokens(handle, it) }
            val fitted = trimmed.prompt ?: error("转写太长，装不进上下文（预算 $budget token）：请缩短转写或换更大的上下文")
            // 取消要能真的打断原生解码：一次长会议生成要两分钟，只在协程层面 cancel
            // 的话，原生那一段还会跑完、还会写出纪要
            val registration = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                ?.invokeOnCompletion { LlamaBridge.cancel(handle) }
            val text = try {
                LlamaBridge.generate(handle, fitted, outputTokens)
            } finally {
                registration?.dispose()
            }
            if (text.isEmpty()) return text
            // 裁剪必须让用户看得见：拿着残缺的纪要却以为开完了会，比报错更糟
            val disclosed = if (trimmed.trimmed) {
                val total = trimmed.keptLines + trimmed.droppedLines
                val percent = if (total <= 0) "" else "（约 ${trimmed.keptLines * 100 / total}%）"
                text.trimEnd() + "\n\n> 转写过长，本次纪要与待办只覆盖会议前段${percent}的内容。\n"
            } else {
                text
            }
            onToken(disclosed)
            return disclosed
        } finally {
            LlamaBridge.free(handle)
        }
    }
}
