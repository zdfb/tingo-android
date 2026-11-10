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
    fun switchingProviderClearsTheApiKey() {
        // 上一家的 key 发到这一家只会 401，更糟的是等于把 A 家的密钥送给了 B 家
        val config = LlmConfig(baseUrl = "https://api.deepseek.com", apiKey = "sk-DeepSeek的")

        val filled = LlmPresets.find("openai")!!.applyTo(config)

        assertEquals("https://api.openai.com/v1", filled.baseUrl)
        assertEquals("gpt-4.1-mini", filled.model)
        assertEquals("换服务商必须清掉密钥", "", filled.apiKey)
    }

    @Test
    fun stayingWithTheSameProviderKeepsTheApiKey() {
        // 同一家的另一个模型：密钥还是那把，没理由让用户再粘一次
        val config = LlmConfig(baseUrl = "https://api.deepseek.com/", apiKey = "sk-DeepSeek的")

        val filled = LlmPresets.find("deepseek")!!.applyTo(config)

        assertEquals("sk-DeepSeek的", filled.apiKey)
    }

    @Test
    fun matchingIgnoresCaseAndTrailingSlashes() {
        assertEquals("openai", LlmPresets.match(LlmConfig(baseUrl = "https://api.openai.com/v1/")))
        assertEquals("deepseek", LlmPresets.match(LlmConfig(baseUrl = " HTTPS://API.DeepSeek.com// ")))
        // 归一化只用来判断"是不是同一个地址"，用户填的原文一个字都不改
        val typed = LlmConfig(baseUrl = "HTTPS://API.DeepSeek.com/")
        assertEquals("HTTPS://API.DeepSeek.com/", typed.baseUrl)
        assertEquals("https://api.deepseek.com", LlmPresets.normalize(typed.baseUrl))
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
