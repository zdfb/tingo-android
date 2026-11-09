package io.github.tancheng33.tingo.minutes.remote

import io.github.tancheng33.tingo.settings.LlmConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 向用户配置的模型服务要一段文本。
 *
 * 纪要生成与"猜说话人名字"要问的是同一个服务、同一套鉴权，差别只在提示词，
 * 所以把"发请求 + 翻译错误 + 取正文"收在一处；两套实现必然漂移。
 */
class LlmChat(
    private val configProvider: () -> LlmConfig,
    private val client: HttpJsonClient,
) {
    /**
     * @param system 系统指令；为空时按各协议省略
     * @throws LlmUnavailable 连不上服务（可重试）
     * @throws LlmRejected 服务拒绝（401/402/429…，带服务端原话）
     */
    suspend fun complete(prompt: String, maxTokens: Int, system: String = ""): String {
        val config = configProvider()
        require(config.isConfigured) { "尚未配置模型服务（地址、密钥、模型名都要填）" }

        val request = LlmRequests.build(config, prompt, maxTokens, system)
        val raw = try {
            client.post(request.url, request.headers, request.body)
        } catch (e: HttpJsonClient.HttpFailure) {
            val detail = LlmRequests.parseError(e.body)
            throw LlmRejected("（HTTP ${e.code}）${detail ?: "服务端没有给出原因"}")
        } catch (e: HttpJsonClient.Unreachable) {
            throw LlmUnavailable(e.message ?: "网络不可达", e)
        }
        // HTTP 读是阻塞的：回来先确认协程还活着，取消后不要再写文件
        currentCoroutineContext().ensureActive()

        val text = LlmRequests.parseText(config, raw)
        check(!text.isNullOrBlank()) { "模型服务返回了空内容" }
        check(!LlmRequests.isTruncated(config, raw)) { "模型输出被长度上限截断" }
        return text
    }
}
