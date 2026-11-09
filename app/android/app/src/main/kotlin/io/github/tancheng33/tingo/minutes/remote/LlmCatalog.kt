package io.github.tancheng33.tingo.minutes.remote

import io.github.tancheng33.tingo.settings.LlmConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * 拉取用户配置的模型服务上有哪些模型。
 *
 * 桌面设置页有"从服务商拉模型列表"，用户不用手抄模型名；手机上手抄更难受，所以这一条必须在。
 * 地址与鉴权头复用 [LlmRequests]（与生成纪要走同一份拼装逻辑），失败原因照原话给用户看。
 *
 * 注意：这里吃的是**表单里正在填的**配置，不要求已经能生成——这个按钮存在的意义
 * 正是"还没配好模型名时，先问服务端有哪些"。所以只要求地址非空。
 */
class LlmCatalog(private val client: HttpJsonClient) {

    /**
     * @throws LlmUnavailable 连不上服务
     * @throws LlmRejected 服务拒绝（密钥不对、没权限）
     * @throws IllegalStateException 没填地址，或返回里没有可用的模型名
     */
    fun list(config: LlmConfig): List<String> {
        require(config.baseUrl.isNotBlank()) { "还没填服务地址" }
        val request = LlmRequests.modelsRequest(config)
        val body = try {
            client.get(request.url, request.headers)
        } catch (e: HttpJsonClient.HttpFailure) {
            throw LlmRejected("（HTTP ${e.code}）${LlmRequests.parseError(e.body) ?: "服务端没有给出原因"}")
        } catch (e: HttpJsonClient.Unreachable) {
            throw LlmUnavailable(e.message ?: "网络不可达", e)
        }
        return parse(body)
    }

    /** 从响应里取模型名：按 `data[].id` 取（两种协议同形），去空、去重、排序。 */
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
