package io.github.tancheng33.tingo.minutes.remote

import io.github.tancheng33.tingo.minutes.MinutesGenerator
import io.github.tancheng33.tingo.settings.LlmConfig

/**
 * 远端纪要生成器：把提示词发给用户配置的模型服务，取回 Markdown。
 *
 * 错误信息要能直接指导用户下一步：连不上 → 检查网络；401 → 检查密钥；
 * 余额/限流 → 服务端原话。笼统的"生成失败"会让人无从下手。
 */
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
            error("模型服务拒绝了请求（HTTP ${e.code}）${if (detail != null) "：$detail" else ""}")
        } catch (e: HttpJsonClient.Unreachable) {
            error("连不上模型服务，请检查网络或服务地址（${e.message}）")
        }

        val text = LlmRequests.parseText(config, raw)
        check(!text.isNullOrBlank()) { "模型服务返回了空内容" }
        onToken(text)
        return text
    }
}
