package io.github.tancheng33.tingo.models

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 真网络实现：HTTP Range 续传 + 明确超时。
 *
 * 只做两件事——按偏移取流、告诉调用方服务器是否接受了 Range。
 * 重试与校验交给 [ModelStore]：网络层不该偷偷重下整份文件。
 */
class UrlFetcher(
    private val connectTimeoutMs: Int = 20_000,
    // 模型动辄几百兆、只走镜像；读超时给宽一点，否则慢镜像上 227 MB 的文件会在中途被判超时
    private val readTimeoutMs: Int = 60_000,
) : HttpFetcher {

    override fun open(url: String, rangeStart: Long): HttpFetcher.FetchedStream {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            if (rangeStart > 0) setRequestProperty("Range", "bytes=$rangeStart-")
        }
        val code = connection.responseCode
        val resumed = rangeStart > 0 && code == HttpURLConnection.HTTP_PARTIAL
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect()
            error("下载失败：HTTP $code $url")
        }
        return HttpFetcher.FetchedStream(connection.inputStream.withDisconnect(connection), resumed)
    }

    /** 流读完/关掉时一起断开连接，避免连接池里挂着半开的 socket。 */
    private fun InputStream.withDisconnect(connection: HttpURLConnection): InputStream =
        object : InputStream() {
            override fun read(): Int = this@withDisconnect.read()

            override fun read(b: ByteArray, off: Int, len: Int): Int = this@withDisconnect.read(b, off, len)

            override fun close() {
                try {
                    this@withDisconnect.close()
                } finally {
                    connection.disconnect()
                }
            }
        }
}
