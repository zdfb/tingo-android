package io.github.tancheng33.tingo.minutes.remote

import io.github.tancheng33.tingo.settings.LlmConfig
import io.github.tancheng33.tingo.settings.LlmProtocol
import org.json.JSONArray
import org.json.JSONObject

/**
 * 两种协议的报文拼装与解析。
 *
 * 字段名写错时服务端只会回 400，而手机屏幕上显示的是"生成失败"——很难查。
 * 所以这些键名集中在这里，并有单测逐个断言。
 */
object LlmRequests {

    /** 一次请求的完整描述：单测与真实请求共用同一份，避免"测的和跑的不是一回事"。 */
    data class Request(val url: String, val headers: Map<String, String>, val body: String)

    fun build(config: LlmConfig, prompt: String, maxTokens: Int, system: String = ""): Request {
        val base = config.baseUrl.trimEnd('/').removeSuffix("/v1")
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
        return when (config.protocol) {
            LlmProtocol.OPENAI -> Request(
                url = "$base/v1/chat/completions",
                headers = mapOf(
                    "Authorization" to "Bearer ${config.apiKey}",
                ),
                body = JSONObject()
                    .put("model", config.model)
                    .put("max_tokens", maxTokens)
                    .put("messages", if (system.isNotBlank()) withSystem(system, messages) else messages)
                    .toString(),
            )

            LlmProtocol.ANTHROPIC -> Request(
                url = "$base/v1/messages",
                headers = mapOf(
                    "x-api-key" to config.apiKey,
                    "anthropic-version" to "2023-06-01",
                ),
                body = JSONObject()
                    .put("model", config.model)
                    .put("max_tokens", maxTokens)
                    .put("messages", messages)
                    // Anthropic 把 system 放顶层字段，不放 messages 里
                    .apply { if (system.isNotBlank()) put("system", system) }
                    .toString(),
            )
        }
    }

    /** OpenAI 兼容协议里 system 是 messages 的第一条。 */
    private fun withSystem(system: String, messages: JSONArray): JSONArray =
        JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(messages.get(0))

    /** 取正文；两种协议的字段位置不同。 */
    fun parseText(config: LlmConfig, raw: String): String? = when (config.protocol) {
        LlmProtocol.OPENAI ->
            JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")?.takeIf { it.isNotBlank() }

        LlmProtocol.ANTHROPIC ->
            JSONObject(raw).optJSONArray("content")?.let { array ->
                (0 until array.length())
                    .mapNotNull { array.optJSONObject(it) }
                    .firstOrNull { it.optString("type") == "text" }
                    ?.optString("text")?.takeIf { it.isNotBlank() }
            }
    }

    /** 响应是否因为长度上限被截断；两种协议的字段不同。 */
    fun isTruncated(config: LlmConfig, raw: String): Boolean = runCatching {
        when (config.protocol) {
            LlmProtocol.OPENAI ->
                JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)?.optString("finish_reason") == "length"

            LlmProtocol.ANTHROPIC ->
                JSONObject(raw).optString("stop_reason") == "max_tokens"
        }
    }.getOrDefault(false)

    /**
     * 从错误正文里挖出能给用户看的一句话。
     *
     * 拿不到结构化的 `error.message` 时退回原文（截断），总比只说"HTTP 401"有用。
     */
    fun parseError(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val message = runCatching {
            JSONObject(text).optJSONObject("error")?.optString("message")?.ifBlank { null }
                ?: JSONObject(text).optString("message").ifBlank { null }
                ?: JSONObject(text).optString("detail").ifBlank { null }
        }.getOrNull()
        return message ?: text.take(300)
    }
}
