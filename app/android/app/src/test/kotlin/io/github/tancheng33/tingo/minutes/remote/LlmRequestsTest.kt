package io.github.tancheng33.tingo.minutes.remote

import io.github.tancheng33.tingo.settings.LlmConfig
import io.github.tancheng33.tingo.settings.LlmProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两种协议的请求体、鉴权头与响应解析。
 *
 * 这里刻意断言字段名：`chat/completions` 与 `messages` 的字段不一样，写错一个键
 * 服务端只会回 400，而手机上看到的就是"生成失败"，很难查。
 */
class LlmRequestsTest {

    private val openai = LlmConfig(
        baseUrl = "https://api.example.com",
        apiKey = "sk-test",
        model = "deepseek-chat",
        protocol = LlmProtocol.OPENAI,
    )

    private val anthropic = openai.copy(protocol = LlmProtocol.ANTHROPIC, model = "claude-sonnet-4-5")

    @Test
    fun openAiRequestUsesBearerAuthAndChatCompletionsPath() {
        val request = LlmRequests.build(openai, prompt = "写纪要", maxTokens = 1200)

        assertEquals("https://api.example.com/v1/chat/completions", request.url)
        assertEquals("Bearer sk-test", request.headers["Authorization"])
        val body = JSONObject(request.body)
        assertEquals("deepseek-chat", body.getString("model"))
        assertEquals(1200, body.getInt("max_tokens"))
        val message = body.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", message.getString("role"))
        assertEquals("写纪要", message.getString("content"))
    }

    @Test
    fun baseUrlMayAlreadyCarryVPrefixOrTrailingSlash() {
        val withSlash = LlmRequests.build(openai.copy(baseUrl = "https://api.example.com/"), "p", 10)
        val withV1 = LlmRequests.build(openai.copy(baseUrl = "https://api.example.com/v1"), "p", 10)

        assertEquals("https://api.example.com/v1/chat/completions", withSlash.url)
        assertEquals("https://api.example.com/v1/chat/completions", withV1.url)
    }

    @Test
    fun anthropicRequestUsesApiKeyHeaderAndMessagesPath() {
        val request = LlmRequests.build(anthropic, prompt = "写纪要", maxTokens = 1200)

        assertEquals("https://api.example.com/v1/messages", request.url)
        assertEquals("sk-test", request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        val body = JSONObject(request.body)
        assertEquals("claude-sonnet-4-5", body.getString("model"))
        assertEquals(1200, body.getInt("max_tokens"))
        assertEquals("user", body.getJSONArray("messages").getJSONObject(0).getString("role"))
    }

    @Test
    fun parsesOpenAiResponse() {
        val raw = """{"choices":[{"message":{"role":"assistant","content":"# 标题\n\n## 一句话总结\n好"}}]}"""
        assertEquals("# 标题\n\n## 一句话总结\n好", LlmRequests.parseText(openai, raw))
    }

    @Test
    fun parsesAnthropicResponse() {
        val raw = """{"content":[{"type":"text","text":"# 标题\n\n## 一句话总结\n好"}]}"""
        assertEquals("# 标题\n\n## 一句话总结\n好", LlmRequests.parseText(anthropic, raw))
    }

    @Test
    fun parsesErrorMessageFromBody() {
        val raw = """{"error":{"message":"Insufficient balance","type":"insufficient_balance"}}"""
        assertTrue(LlmRequests.parseError(raw)!!.contains("Insufficient balance"))
    }

    @Test
    fun unparsableErrorBodyFallsBackToRawText() {
        assertEquals("502 Bad Gateway", LlmRequests.parseError("502 Bad Gateway")!!)
    }
}
