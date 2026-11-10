package io.github.tancheng33.tingo.minutes.remote

import io.github.tancheng33.tingo.settings.LlmConfig
import io.github.tancheng33.tingo.settings.LlmProtocol

/**
 * 常用服务商预设：点一下填好地址、协议、模型名。
 *
 * 与桌面 `settings.ts` 的 `remotePresets()` 同一份清单、同一个顺序（"自定义"除外，
 * 它不是一家服务商）——用户在两台设备上看到的是同样的名字，不必怀疑"手机版少了哪家"。
 *
 * 桌面的"本机中转"预设（Magpie、CC Switch、Ollama）不在这里：它们指向 127.0.0.1，
 * 在手机上那个 127.0.0.1 是手机自己，填进去只会得到"连不上"。
 *
 * 预设只是**填空**，不是绑定：地址与模型名都能改，也能选"自定义"自己填。
 */
data class LlmPreset(
    val id: String,
    /** 稳定的中文名；英文界面另取 */
    val nameZh: String,
    val nameEn: String,
    val baseUrl: String,
    val model: String,
    val protocol: LlmProtocol = LlmProtocol.OPENAI,
) {
    fun name(english: Boolean): String = if (english) nameEn else nameZh

    /**
     * 选中这个预设后的配置。
     *
     * **换服务商要清掉密钥**：上一家的 key 发到这一家只会得到 401，而且等于把 A 家的密钥
     * 主动送给 B 家。地址没变（同一家的另一个模型）时才保留——桌面端也是这么做的。
     */
    fun applyTo(config: LlmConfig): LlmConfig = config.copy(
        baseUrl = baseUrl,
        model = model,
        protocol = protocol,
        apiKey = if (LlmPresets.normalize(config.baseUrl) == LlmPresets.normalize(baseUrl)) config.apiKey else "",
    )
}

object LlmPresets {

    const val CUSTOM_ID = "custom"

    val all: List<LlmPreset> = listOf(
        LlmPreset("deepseek", "DeepSeek", "DeepSeek", "https://api.deepseek.com", "deepseek-chat"),
        LlmPreset(
            "bailian",
            "通义千问（百炼）",
            "Qwen (Bailian)",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "qwen-plus",
        ),
        LlmPreset("openai", "OpenAI", "OpenAI", "https://api.openai.com/v1", "gpt-4.1-mini"),
        LlmPreset("claude", "Claude", "Claude", "https://api.anthropic.com/v1", "claude-sonnet-5", LlmProtocol.ANTHROPIC),
        LlmPreset(
            "gemini",
            "Gemini",
            "Gemini",
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-2.5-flash",
        ),
    )

    /**
     * 当前配置对应哪个预设。
     *
     * 按地址判断，比桌面宽松一点（桌面只去末尾斜杠，这里还忽略大小写与多个末尾斜杠）：
     * 只影响"哪个预设高亮"，不影响发出去的请求。认不出来就是"自定义"——
     * 用户自己填的地址不该硬说成某个服务商。
     */
    fun match(config: LlmConfig): String {
        val want = normalize(config.baseUrl)
        if (want.isEmpty()) return CUSTOM_ID
        return all.firstOrNull { normalize(it.baseUrl) == want }?.id ?: CUSTOM_ID
    }

    fun find(id: String): LlmPreset? = all.firstOrNull { it.id == id }

    /** 归一化：只为了"是不是同一个地址"这个判断，不改用户填的原文。 */
    internal fun normalize(url: String): String = url.trim().trimEnd('/').lowercase()
}
