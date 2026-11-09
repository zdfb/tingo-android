package io.github.tancheng33.tingo.minutes.remote

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 发一次 JSON 请求并拿回响应正文。
 *
 * 单独抽成端口：生成器的错误映射可以用假实现覆盖，不必真的连网；
 * 真实实现只有 [UrlHttpJsonClient] 一个。
 */
interface HttpJsonClient {
    fun post(url: String, headers: Map<String, String>, body: String): String

    /** 拉取模型列表是 GET；服务端对 POST /models 一般只回 405。 */
    fun get(url: String, headers: Map<String, String>): String

    /** 服务端明确拒绝：状态码 + 响应正文（正文里通常有能直接给用户看的原因）。 */
    class HttpFailure(val code: Int, val body: String) : Exception("HTTP $code")

    /** 连不上：超时、DNS 失败、连接被拒。与 [HttpFailure] 区分，因为用户要做的事不同。 */
    class Unreachable(message: String, cause: Throwable? = null) : Exception(message, cause)
}

/**
 * 真网络实现。
 *
 * 超时给得比模型下载宽：纪要的响应是模型实时生成，一分钟内算正常，
 * 但超过两分钟基本可以判定服务端卡住了。
 */
class UrlHttpJsonClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 120_000,
) : HttpJsonClient {

    override fun post(url: String, headers: Map<String, String>, body: String): String =
        send("POST", url, headers, body)

    override fun get(url: String, headers: Map<String, String>): String = send("GET", url, headers, null)

    private fun send(method: String, url: String, headers: Map<String, String>, body: String?): String {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
                if (body != null) {
                    doOutput = true
                    if (getRequestProperty("Content-Type") == null) {
                        setRequestProperty("Content-Type", "application/json")
                    }
                }
            }
        } catch (e: IOException) {
            throw HttpJsonClient.Unreachable("连接 $url 失败：${e.message}", e)
        }

        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpJsonClient.HttpFailure(code, text)
            return text
        } catch (e: HttpJsonClient.HttpFailure) {
            throw e
        } catch (e: IOException) {
            throw HttpJsonClient.Unreachable("连不上模型服务：${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }
}
