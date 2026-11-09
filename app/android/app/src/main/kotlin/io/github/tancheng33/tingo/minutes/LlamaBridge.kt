package io.github.tancheng33.tingo.minutes

/**
 * llama.cpp 的 JNI 入口。
 *
 * 只在这一层碰原生代码：上层拿到的是一个"给提示词、返回文本"的函数，
 * 所以提示词与输出校验都能在 JVM 单测里覆盖。
 */
object LlamaBridge {

    /** 原生库是否加载成功；x86_64 等没有内置库的 ABI 上会是 false。 */
    val isAvailable: Boolean = runCatching {
        System.loadLibrary("tingo_llama")
        true
    }.getOrElse { false }

    /**
     * @param modelPath gguf 文件的绝对路径
     * @param nThreads 生成线程数；手机上 4 个通常比 8 个快
     * @param nCtx 上下文长度，纪要场景 4096 够用
     * @return 句柄，0 表示失败
     */
    fun load(modelPath: String, nThreads: Int = 4, nCtx: Int = 4096): Long =
        if (isAvailable) nativeLoad(modelPath, nThreads, nCtx) else 0L

    /** 一次性生成；空字符串表示失败。 */
    fun generate(handle: Long, prompt: String, maxTokens: Int = 1_200): String =
        if (handle == 0L || !isAvailable) "" else nativeGenerate(handle, prompt, maxTokens)

    fun free(handle: Long) {
        if (handle != 0L && isAvailable) nativeFree(handle)
    }

    /**
     * 基准测试专用：分开报预填充与解码的 token 数与耗时。
     *
     * 不拿"总耗时 ÷ 字符数"估算：那是把预填充也算进生成速度里，出来的数会骗人。
     */
    fun bench(handle: Long, prompt: String, maxTokens: Int = 256): Bench? {
        if (handle == 0L || !isAvailable) return null
        val raw = nativeBench(handle, prompt, maxTokens)
        val parts = raw.split(',')
        if (parts.size != 4) return null
        return Bench(
            promptTokens = parts[0].toIntOrNull() ?: return null,
            prefillMs = parts[1].toLongOrNull() ?: return null,
            decodeTokens = parts[2].toIntOrNull() ?: return null,
            decodeMs = parts[3].toLongOrNull() ?: return null,
        )
    }

    /** 一次基准测量的原始数据。 */
    data class Bench(
        val promptTokens: Int,
        val prefillMs: Long,
        val decodeTokens: Int,
        val decodeMs: Long,
    ) {
        /** 预填充速度（token/s）；耗时 0 时按"极快"处理，避免除零。 */
        val prefillTokensPerSecond: Double
            get() = if (prefillMs <= 0) Double.POSITIVE_INFINITY else promptTokens * 1000.0 / prefillMs

        /** 生成速度（token/s），这是用户体感最直接的那个数。 */
        val decodeTokensPerSecond: Double
            get() = if (decodeMs <= 0) Double.POSITIVE_INFINITY else decodeTokens * 1000.0 / decodeMs

        /** 首字延迟：等预填充完成，用户才看到第一个字。 */
        val timeToFirstTokenMs: Long get() = prefillMs
    }

    /**
     * 这段文本占多少 token。
     *
     * 提示词预算按 token 算：按字符估会在长会议上直接超上下文（中文 1 字≈1 token，
     * 但中英混排、时间戳、表格符号都会拉高），而超上下文的后果是生成失败而不是截断。
     */
    fun countTokens(handle: Long, text: String): Int =
        if (handle == 0L || !isAvailable) 0 else nativeCountTokens(handle, text)

    private external fun nativeLoad(modelPath: String, nThreads: Int, nCtx: Int): Long

    private external fun nativeCountTokens(handle: Long, text: String): Int

    private external fun nativeBench(handle: Long, prompt: String, maxTokens: Int): String

    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String

    private external fun nativeFree(handle: Long)
}
