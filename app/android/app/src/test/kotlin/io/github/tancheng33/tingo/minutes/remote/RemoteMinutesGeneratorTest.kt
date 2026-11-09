package io.github.tancheng33.tingo.minutes.remote

import io.github.tancheng33.tingo.settings.LlmConfig
import io.github.tancheng33.tingo.settings.LlmProtocol
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远端生成器的错误映射：用户屏幕上看到的那句话，取决于这里怎么翻译。
 *
 * "连不上服务"和"密钥不对"是用户要做的事完全不同的两种情况，不能都报"生成失败"。
 */
class RemoteMinutesGeneratorTest {

    private val config = LlmConfig(
        baseUrl = "https://api.example.com",
        apiKey = "sk-test",
        model = "m",
        protocol = LlmProtocol.OPENAI,
    )

    private class FakeClient(
        private val reply: () -> String,
    ) : HttpJsonClient {
        var lastUrl: String? = null

        override fun post(url: String, headers: Map<String, String>, body: String): String {
            lastUrl = url
            return reply()
        }
    }

    private fun generator(client: HttpJsonClient, config: LlmConfig = this.config) =
        RemoteMinutesGenerator({ config }, client)

    @Test
    fun returnsModelText() = runTest {
        val client = FakeClient { """{"choices":[{"message":{"content":"# 标题"}}]}""" }
        assertEquals("# 标题", generator(client).generate("写纪要", 1200))
    }

    @Test
    fun reportsServerMessageOnRejection() = runTest {
        val client = FakeClient { throw HttpJsonClient.HttpFailure(401, """{"error":{"message":"Invalid API key"}}""") }
        val error = runCatching { generator(client).generate("写纪要", 1200) }.exceptionOrNull()
        assertTrue("应带上服务端的原话，实际：${error?.message}", error?.message?.contains("Invalid API key") == true)
    }

    @Test
    fun unreachableSurfacesAsTypedFailure() = runTest {
        val client = FakeClient { throw HttpJsonClient.Unreachable("timeout") }
        val error = runCatching { generator(client).generate("写纪要", 1200) }.exceptionOrNull()
        // 类型化：界面据此选文案，不用猜中文子串
        assertTrue("连不上应抛 LlmUnavailable，实际：${error}", error is LlmUnavailable)
    }

    @Test
    fun rejectionSurfacesAsTypedFailure() = runTest {
        val client = FakeClient { throw HttpJsonClient.HttpFailure(429, """{"error":{"message":"rate limited"}}""") }
        val error = runCatching { generator(client).generate("写纪要", 1200) }.exceptionOrNull()
        assertTrue("服务拒绝应抛 LlmRejected，实际：$error", error is LlmRejected)
        assertTrue("要带上服务端原话", error?.message?.contains("rate limited") == true)
    }

    @Test
    fun truncatedOutputIsReportedAsSuch() = runTest {
        // finish_reason=length：再重的试也只是再截一次，必须如实说"被截断"
        val client = FakeClient {
            """{"choices":[{"message":{"content":"# 标题"},"finish_reason":"length"}]}"""
        }
        val error = runCatching { generator(client).generate("写纪要", 1200) }.exceptionOrNull()
        assertTrue("应提示被长度上限截断，实际：${error?.message}", error?.message?.contains("截断") == true)
    }

    @Test
    fun rejectsEmptyContent() = runTest {
        val client = FakeClient { """{"choices":[{"message":{"content":""}}]}""" }
        val error = runCatching { generator(client).generate("写纪要", 1200) }.exceptionOrNull()
        assertTrue("空正文要如实失败", error != null)
    }
}
