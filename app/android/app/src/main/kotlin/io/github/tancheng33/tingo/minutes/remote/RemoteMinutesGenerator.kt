package io.github.tancheng33.tingo.minutes.remote

import io.github.tancheng33.tingo.minutes.MinutesGenerator
import io.github.tancheng33.tingo.settings.LlmConfig

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
    configProvider: () -> LlmConfig,
    client: HttpJsonClient,
) : MinutesGenerator {

    private val chat = LlmChat(configProvider, client)

    override suspend fun generate(prompt: String, maxTokens: Int, onToken: (String) -> Unit): String {
        val text = chat.complete(prompt, maxTokens)
        onToken(text)
        return text
    }
}
