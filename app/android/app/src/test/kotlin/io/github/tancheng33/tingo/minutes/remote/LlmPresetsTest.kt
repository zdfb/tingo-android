package io.github.tancheng33.tingo.minutes.remote

import io.github.tancheng33.tingo.settings.LlmConfig
import io.github.tancheng33.tingo.settings.LlmProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务商预设：清单与桌面一致，填空不绑定，认不出来就是"自定义"。
 */
class LlmPresetsTest {

    @Test
    fun presetsMatchTheDesktopListInOrder() {
        assertEquals(
            listOf("deepseek", "bailian", "openai", "claude", "gemini"),
            LlmPresets.all.map { it.id },
        )
    }

    @Test
    fun claudePresetUsesTheAnthropicProtocol() {
        val claude = LlmPresets.find("claude")!!

        assertEquals(LlmProtocol.ANTHROPIC, claude.protocol)
        // 其余都是 OpenAI 兼容：填错协议只会得到一个看不懂的 404
        assertTrue(LlmPresets.all.filter { it.id != "claude" }.all { it.protocol == LlmProtocol.OPENAI })
    }

    @Test
    fun applyingAPresetKeepsTheApiKey() {
        val config = LlmConfig(apiKey = "sk-用户自己的")

        val filled = LlmPresets.find("deepseek")!!.applyTo(config)

        assertEquals("https://api.deepseek.com", filled.baseUrl)
        assertEquals("deepseek-chat", filled.model)
        assertEquals("密钥不该被预设覆盖", "sk-用户自己的", filled.apiKey)
    }

    @Test
    fun matchingIgnoresTrailingSlashCaseAndLocalhost() {
        assertEquals("openai", LlmPresets.match(LlmConfig(baseUrl = "https://api.openai.com/v1/")))
        assertEquals("deepseek", LlmPresets.match(LlmConfig(baseUrl = " HTTPS://API.DeepSeek.com ")))
        // 归一化只用来判断"是不是同一个地址"，用户填的原文一个字都不改
        val local = LlmConfig(baseUrl = "http://localhost:11434/v1")
        assertEquals("http://localhost:11434/v1", local.baseUrl)
        assertEquals("http://127.0.0.1:11434/v1", LlmPresets.normalize(local.baseUrl))
    }

    @Test
    fun unknownOrEmptyUrlIsCustom() {
        assertEquals(LlmPresets.CUSTOM_ID, LlmPresets.match(LlmConfig(baseUrl = "")))
        assertEquals(
            LlmPresets.CUSTOM_ID,
            LlmPresets.match(LlmConfig(baseUrl = "https://my-own-gateway.example.com/v1")),
        )
    }

    @Test
    fun namesAreLocalized() {
        val qwen = LlmPresets.find("bailian")!!

        assertEquals("通义千问（百炼）", qwen.name(english = false))
        assertEquals("Qwen (Bailian)", qwen.name(english = true))
    }
}
