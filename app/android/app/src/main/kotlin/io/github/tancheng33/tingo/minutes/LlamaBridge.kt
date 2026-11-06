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

    private external fun nativeLoad(modelPath: String, nThreads: Int, nCtx: Int): Long

    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String

    private external fun nativeFree(handle: Long)
}
