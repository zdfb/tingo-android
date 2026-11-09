package io.github.tancheng33.tingo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tancheng33.tingo.minutes.MinutesService
import io.github.tancheng33.tingo.minutes.remote.RemoteMinutesGenerator
import io.github.tancheng33.tingo.minutes.remote.UrlHttpJsonClient
import io.github.tancheng33.tingo.settings.LlmConfig
import io.github.tancheng33.tingo.settings.LlmProtocol
import io.github.tancheng33.tingo.transcription.Transcript
import io.github.tancheng33.tingo.transcription.TranscriptSentence
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机上的远端纪要链路：请求真的发出去、响应真的写成了 `纪要.md`。
 *
 * 用本机回环的假服务端，而不是真的调服务商：这条用例要在没有密钥、没有外网的机器上
 * 也能跑，验证的是"报文拼对了、返回解析对了、文件写对了"，而不是某家服务今天的可用性。
 */
@RunWith(AndroidJUnit4::class)
class RemoteMinutesInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** 一段合格的五章节纪要，自检能过。 */
    private val markdown = """
        # 注册页复盘

        ## 一句话总结
        转化率下滑由三个原因叠加。

        ## 议题与讨论
        ### 1. 转化率
        - 从 12% 降到 8.5%（[00:00]）

        ## 决议
        - 先修首屏（[00:10]）

        ## 待办
        | 事项 | 负责人 | 截止时间 | 出处 |
        |---|---|---|---|
        | 修首屏 | 林舟 | 未明确 | [00:10] |

        ## 待定问题与风险
        - 无
    """.trimIndent()

    private fun transcript(): Transcript = Transcript(
        source = "录音.wav",
        durationMs = 120_000,
        sentences = (0 until 6).map {
            TranscriptSentence(speaker = 0, startMs = it * 20_000L, endMs = it * 20_000L + 15_000L, text = "第${it + 1}段讨论转化率")
        },
    )

    @Test
    fun endToEndRequestProducesMinutesFile() {
        val server = ServerSocket(0)
        val port = server.localPort
        var receivedPath: String? = null
        var receivedAuth: String? = null
        var receivedBody: String? = null

        val serving = thread(isDaemon = true) {
            runCatching {
                server.soTimeout = 20_000
                val accepted = server.accept()
                accepted.use { socket ->
                    // 只从原始流读：BufferedReader 会把请求体的一部分缓冲走，
                    // 之后再从原始流按 Content-Length 读就会永远等不到那些字节（实测挂死）
                    val input = socket.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) {
                        val b = input.read()
                        if (b < 0) break
                        head.append(b.toChar())
                    }
                    val lines = head.split("\r\n")
                    val requestLine = lines.firstOrNull().orEmpty()
                    val headers = lines.drop(1)
                        .mapNotNull { line -> line.split(": ", limit = 2).takeIf { it.size == 2 } }
                        .associate { it[0].lowercase() to it[1] }
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    val bodyBytes = ByteArray(length)
                    var received = 0
                    while (received < length) {
                        val n = input.read(bodyBytes, received, length - received)
                        if (n <= 0) break
                        received += n
                    }
                    receivedPath = requestLine.split(" ").getOrNull(1)
                    receivedAuth = headers["authorization"]
                    receivedBody = String(bodyBytes, 0, received, Charsets.UTF_8)

                    val payload = """{"choices":[{"message":{"role":"assistant","content":${quote(markdown)}}}]}"""
                    val bytes = payload.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n".toByteArray())
                        write("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }

        val dir = File(context.cacheDir, "remote-minutes-test").apply { deleteRecursively(); mkdirs() }
        val config = LlmConfig(
            baseUrl = "http://127.0.0.1:$port",
            apiKey = "sk-local",
            model = "test-model",
            protocol = LlmProtocol.OPENAI,
        )
        val generator = RemoteMinutesGenerator(
            { config },
            UrlHttpJsonClient(connectTimeoutMs = 15_000, readTimeoutMs = 30_000),
        )
        val service = MinutesService(generator)

        val file = try {
            runBlocking { service.generate(transcript(), dir) }
        } finally {
            // 断言失败也要收掉线程与端口，别让一条红用例把后面几条一起拖红
            serving.join(5_000)
            server.close()
        }

        assertTrue("应当写出 纪要.md", file.isFile)
        assertTrue("纪要内容应来自服务端响应", file.readText().contains("## 一句话总结"))
        assertTrue("路径应打到 chat/completions，实际：$receivedPath", receivedPath == "/v1/chat/completions")
        assertTrue("鉴权头应带上密钥，实际：$receivedAuth", receivedAuth == "Bearer sk-local")
        assertTrue("请求体应包含提示词转写", receivedBody?.contains("转写") == true)
        assertTrue("请求体应包含模型名", receivedBody?.contains("test-model") == true)
    }

    private fun quote(text: String): String =
        org.json.JSONObject.quote(text)
}
