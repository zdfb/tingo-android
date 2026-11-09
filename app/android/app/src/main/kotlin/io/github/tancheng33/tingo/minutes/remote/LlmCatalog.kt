package io.github.tancheng33.tingo.minutes.remote

import io.github.tancheng33.tingo.settings.LlmConfig
import io.github.tancheng33.tingo.settings.LlmProtocol
import org.json.JSONArray
import org.json.JSONObject

/**
 * 拉取用户配置的模型服务上有哪些模型。
 *
 * 桌面设置页给了"从服务商拉模型列表"（`/api/llm/models`），用户不用手抄模型名；
 * 手机上手抄更难受，所以这一条必须在。
 *
 * OpenAI 兼容协议读 `GET {base}/v1/models` 的 `data[].id`；Anthropic 读 `GET {base}/v1/models`
 * 的 `data[].id`（同一形状），两者都要带各自的鉴权头。
 */
class LlmCatalog(private val client: HttpJsonClient) {

    /**
     * @throws LlmUnavailable 连不上服务
     * @throws LlmRejected 服务拒绝（密钥不对、没权限）
     * @throws IllegalStateException 返回里没有可用的模型名
     */
    fun list(config: LlmConfig): List<String> {
        require(config.isConfigured) { "尚未配置模型服务（地址、密钥、模型名都要填）" }
        val base = config.baseUrl.trimEnd('/').removeSuffix("/v1")
        val headers = when (config.protocol) {
            LlmProtocol.OPENAI -> mapOf(
                "Authorization" to "Bearer ${config.apiKey}",
                "Content-Type" to "application/json",
            )
            LlmProtocol.ANTHROPIC -> mapOf(
                "x-api-key" to config.apiKey,
                "anthropic-version" to "2023-06-01",
                "Content-Type" to "application/json",
            )
        }
        val body = try {
            client.post("$base/v1/models", headers, "")
        } catch (e: HttpJsonClient.HttpFailure) {
            throw LlmRejected("（HTTP ${e.code}）${LlmRequests.parseError(e.body) ?: "服务端没有给出原因"}")
        } catch (e: HttpJsonClient.Unreachable) {
            throw LlmUnavailable(e.message ?: "网络不可达", e)
        }
        return parse(body)
    }

    /** 从响应里取模型名：按 `data[].id` 取，去空、去重、排序。 */
    fun parse(body: String): List<String> {
        val data: JSONArray = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull()
            ?: error("服务端返回里没有 data 数组")
        val names = (0 until data.length()).mapNotNull { index ->
            data.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }
        }
        check(names.isNotEmpty()) { "服务端没有返回任何模型" }
        return names.distinct().sorted()
    }
}
