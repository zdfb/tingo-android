package io.github.tancheng33.tingo.minutes.remote

import io.github.tancheng33.tingo.settings.LlmConfig
import io.github.tancheng33.tingo.settings.LlmProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拉取模型列表：手机上手抄模型名很容易错，这个按钮是"能不能配好"的关键一步。
 * 这里盯三件事：地址拼对没有、鉴权头带对没有、服务端说得含糊时错误是否可读。
 */
class LlmCatalogTest {

    private class FakeClient(
        private val reply: () -> String,
    ) : HttpJsonClient {
        var url: String? = null
        var headers: Map<String, String> = emptyMap()

        override fun post(url: String, headers: Map<String, String>, body: String): String {
            this.url = url
            this.headers = headers
            return reply()
        }
    }

    private val openai = LlmConfig(
        baseUrl = "https://api.example.com/v1",
        apiKey = "sk-test",
        model = "m",
        protocol = LlmProtocol.OPENAI,
    )

    @Test
    fun listsModelsFromOpenAiCompatibleEndpoint() {
        val client = FakeClient { """{"data":[{"id":"b-model"},{"id":"a-model"},{"id":"a-model"}]}""" }

        val models = LlmCatalog(client).list(openai)

        assertEquals(listOf("a-model", "b-model"), models)
        // baseUrl 已经带 /v1 时不能拼成 /v1/v1
        assertEquals("https://api.example.com/v1/models", client.url)
        assertEquals("Bearer sk-test", client.headers["Authorization"])
    }

    @Test
    fun anthropicUsesItsOwnAuthHeaders() {
        val client = FakeClient { """{"data":[{"id":"claude-x"}]}""" }
        val config = openai.copy(protocol = LlmProtocol.ANTHROPIC, apiKey = "ak")

        val models = LlmCatalog(client).list(config)

        assertEquals(listOf("claude-x"), models)
        assertEquals("ak", client.headers["x-api-key"])
        assertTrue("Anthropic 需要版本头：${client.headers}", client.headers.containsKey("anthropic-version"))
    }

    @Test
    fun rejectedRequestCarriesTheServiceReason() {
        val client = object : HttpJsonClient {
            override fun post(url: String, headers: Map<String, String>, body: String): String =
                throw HttpJsonClient.HttpFailure(401, """{"error":{"message":"invalid api key"}}""")
        }

        val failure = runCatching { LlmCatalog(client).list(openai) }.exceptionOrNull()

        assertTrue("应报服务拒绝：$failure", failure is LlmRejected)
        assertTrue("要带上服务端原话：${failure?.message}", failure?.message?.contains("invalid api key") == true)
    }

    @Test
    fun unreachableServiceIsNotReportedAsRejection() {
        val client = object : HttpJsonClient {
            override fun post(url: String, headers: Map<String, String>, body: String): String =
                throw HttpJsonClient.Unreachable("timeout")
        }

        val failure = runCatching { LlmCatalog(client).list(openai) }.exceptionOrNull()

        // 连不上和密钥不对是用户要做的事完全不同的两种情况
        assertTrue("应报连不上：$failure", failure is LlmUnavailable)
    }

    @Test
    fun emptyOrMalformedReplyExplainsItself() {
        assertEquals(
            "服务端没有返回任何模型",
            runCatching { LlmCatalog(FakeClient { """{"data":[]}""" }).parse("""{"data":[]}""") }
                .exceptionOrNull()?.message,
        )
        assertTrue(
            runCatching { LlmCatalog(FakeClient { "{}" }).parse("{}") }.exceptionOrNull()?.message
                ?.contains("data") == true,
        )
    }
}
