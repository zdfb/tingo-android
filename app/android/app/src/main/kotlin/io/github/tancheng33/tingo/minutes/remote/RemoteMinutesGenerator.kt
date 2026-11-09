package io.github.tancheng33.tingo.minutes.remote

import io.github.tancheng33.tingo.minutes.MinutesGenerator
import io.github.tancheng33.tingo.settings.LlmConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 远端纪要生成器：把提示词发给用户配置的模型服务，取回 Markdown。
 *
 * 错误信息要能直接指导用户下一步：连不上 → 检查网络；401 → 检查密钥；
 * 余额/限流 → 服务端原话。笼统的"生成失败"会让人无从下手。
 */
/** 连不上服务：网络或地址的问题，用户可以重试。 */
class LlmUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 服务拒绝了请求（401/402/429…）：密码或额度的问题，重试通常没用。 */
class LlmRejected(message: String) : Exception(message)

class RemoteMinutesGenerator(
    private val configProvider: () -> LlmConfig,
    private val client: HttpJsonClient,
) : MinutesGenerator {

    override suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): String {
        val config = configProvider()
        require(config.isConfigured) { "尚未配置模型服务（地址、密钥、模型名都要填）" }

        val request = LlmRequests.build(config, prompt, maxTokens)
        val raw = try {
            client.post(request.url, request.headers, request.body)
        } catch (e: HttpJsonClient.HttpFailure) {
            val detail = LlmRequests.parseError(e.body)
            throw LlmRejected("（HTTP ${e.code}）${detail ?: "服务端没有给出原因"}")
        } catch (e: HttpJsonClient.Unreachable) {
            throw LlmUnavailable(e.message ?: "网络不可达", e)
        }

        // 取消要能真的生效：HTTP 读取是阻塞的，回来时先看一眼协程还在不在，
        // 否则用户点了取消、纪要保持写出来
        currentCoroutineContext().ensureActive()

        val text = LlmRequests.parseText(config, raw)
        check(!text.isNullOrBlank()) { "模型服务返回了空内容" }
        // 被长度上限截断的响应预览的是半篇纪要，按"缺失章节"去重试只会再截一次
        check(!LlmRequests.isTruncated(config, raw)) { "模型输出被长度上限截断，请把模型名换成输出更长的版本，或减少转写长度" }
        onToken(text)
        return text
    }
}
