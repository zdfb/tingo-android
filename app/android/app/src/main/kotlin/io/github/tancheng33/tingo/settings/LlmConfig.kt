package io.github.tancheng33.tingo.settings

/** 接口协议，取值与桌面版 `config.toml` 的 `[llm] api` 一致。 */
enum class LlmProtocol(val wire: String) {
    OPENAI("openai"),
    ANTHROPIC("anthropic"),
    ;

    companion object {
        fun fromWire(value: String?): LlmProtocol =
            entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) } ?: OPENAI
    }
}

/**
 * 纪要用的模型服务配置，与桌面版 `[llm]` 段同一套语义。
 *
 * 默认全空：不预置任何服务商，避免把某家的地址和模型名硬编码进产品。
 */
data class LlmConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val protocol: LlmProtocol = LlmProtocol.OPENAI,
) {
    /**
     * 三项都填了才算配好。
     *
     * 只有地址没有模型时也算"没配"——半配置跑起来只会得到一个 400，
     * 不如在点按钮之前就说清楚。
     */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}
