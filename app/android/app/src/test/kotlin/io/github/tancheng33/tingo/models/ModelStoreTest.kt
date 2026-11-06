package io.github.tancheng33.tingo.models

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelStoreTest {

    private fun dir(): File = Files.createTempDirectory("tingo-models").toFile()

    private val payload = ByteArray(10_000) { (it % 251).toByte() }

    private fun model(sha256: String? = null) = ModelFile(
        id = "paraformer",
        fileName = "model.int8.onnx",
        url = "https://example.invalid/model.int8.onnx",
        bytes = payload.size.toLong(),
        sha256 = sha256,
    )

    /** 支持 Range 的假服务器：从 [rangeStart] 开始吐数据。 */
    private class FakeFetcher(
        private val body: ByteArray,
        private val supportsRange: Boolean = true,
        private val failAfter: Int? = null,
    ) : HttpFetcher {
        var requests = mutableListOf<Long>()

        override fun open(url: String, rangeStart: Long): HttpFetcher.FetchedStream {
            requests += rangeStart
            val resumed = supportsRange && rangeStart > 0
            val start = if (resumed) rangeStart.toInt() else 0
            var sent = 0
            val stream = object : InputStream() {
                override fun read(): Int {
                    if (start + sent >= body.size) return -1
                    if (failAfter != null && sent >= failAfter) throw java.io.IOException("连接中断")
                    return body[start + sent++].toInt() and 0xFF
                }
            }
            return HttpFetcher.FetchedStream(stream, resumed)
        }
    }

    @Test
    fun downloadsFromScratchAndReportsProgress() {
        val store = ModelStore(dir(), FakeFetcher(payload))
        val progress = mutableListOf<DownloadProgress>()

        val result = store.download(model(), onProgress = { progress += it })

        assertTrue(result.isSuccess)
        assertEquals(payload.size.toLong(), result.getOrThrow().length())
        assertTrue(progress.isNotEmpty())
        assertEquals(1f, progress.last().fraction, 0.0001f)
        assertTrue(progress.zipWithNext().all { (a, b) -> b.downloaded >= a.downloaded })
    }

    @Test
    fun resumesFromPartialFileWhenServerSupportsRange() {
        val root = dir()
        val file = File(root, "model.int8.onnx")
        file.writeBytes(payload.copyOfRange(0, 4_000)) // 上次下到 4000 字节
        val fetcher = FakeFetcher(payload)

        val result = ModelStore(root, fetcher).download(model())

        assertTrue(result.isSuccess)
        assertEquals(listOf(4_000L), fetcher.requests)
        assertEquals(payload.size.toLong(), file.length())
        assertTrue(file.readBytes().contentEquals(payload))
    }

    @Test
    fun restartsWhenServerIgnoresRange() {
        val root = dir()
        File(root, "model.int8.onnx").writeBytes(payload.copyOfRange(0, 4_000))
        val fetcher = FakeFetcher(payload, supportsRange = false)

        val result = ModelStore(root, fetcher).download(model())

        assertTrue(result.isSuccess)
        assertTrue(File(root, "model.int8.onnx").readBytes().contentEquals(payload))
    }

    @Test
    fun checksumMismatchDeletesFileAndFails() {
        val root = dir()
        val store = ModelStore(root, FakeFetcher(payload))
        val result = store.download(model(sha256 = "0".repeat(64)))

        assertTrue(result.isFailure)
        assertFalse(File(root, "model.int8.onnx").exists())
    }

    @Test
    fun checksumMatchIsAccepted() {
        val root = dir()
        val tmp = File(root, "tmp.bin").apply { writeBytes(payload) }
        val expected = ModelStore.sha256(tmp)
        tmp.delete()
        val store = ModelStore(root, FakeFetcher(payload))

        val result = store.download(model(sha256 = expected))

        assertTrue(result.isSuccess)
        assertTrue(store.isReady(model(sha256 = expected)))
    }

    @Test
    fun interruptedDownloadLeavesPartialForNextAttempt() {
        val root = dir()
        val store = ModelStore(root, FakeFetcher(payload, failAfter = 3_000))

        val first = store.download(model())

        assertTrue(first.isFailure)
        val partial = File(root, "model.int8.onnx")
        assertEquals(3_000L, partial.length())

        val second = ModelStore(root, FakeFetcher(payload)).download(model())
        assertTrue(second.isSuccess)
        assertEquals(payload.size.toLong(), partial.length())
    }

    @Test
    fun readyAndUsedBytesTrackManifestFiles() {
        val root = dir()
        val store = ModelStore(root, FakeFetcher(payload))
        val catalog = ModelCatalog(listOf(model()))

        assertFalse(store.isReady(model()))
        assertEquals(0L, store.usedBytes(catalog))

        store.download(model())

        assertTrue(store.isReady(model()))
        assertEquals(payload.size.toLong(), store.usedBytes(catalog))
        assertEquals(payload.size.toLong(), store.deleteAll(catalog))
        assertFalse(store.isReady(model()))
    }

    @Test
    fun catalogParsesManifestJson() {
        val json = """
            {
              "files": [
                {"id": "paraformer", "file_name": "model.int8.onnx",
                 "url": "https://example.invalid/a.onnx", "bytes": 227000000,
                 "sha256": "abc"},
                {"id": "vad", "file_name": "silero_vad.onnx",
                 "url": "https://example.invalid/vad.onnx", "bytes": 629145}
              ]
            }
        """.trimIndent()

        val catalog = ModelCatalog.parse(json)

        assertEquals(2, catalog.files.size)
        assertNotNull(catalog.byId("paraformer"))
        assertEquals(227_629_145L, catalog.totalBytes)
        assertEquals("abc", catalog.byId("paraformer")!!.sha256)
        assertEquals(null, catalog.byId("vad")!!.sha256)
    }

    @Test
    fun sameSizeButUnverifiedFileIsRedownloaded() {
        val root = dir()
        // 同尺寸的坏文件：体积校验会放过它，所以必须靠"校验指纹"挡住
        File(root, "model.int8.onnx").writeBytes(ByteArray(payload.size))
        val fetcher = FakeFetcher(payload)

        val store = ModelStore(root, fetcher)
        assertFalse("没有指纹的文件不该算就绪", store.isReady(model()))

        val result = store.download(model())

        assertTrue(result.isSuccess)
        assertEquals(listOf(0L), fetcher.requests) // 整份重下，不是续传
        assertTrue(File(root, "model.int8.onnx").readBytes().contentEquals(payload))
        assertTrue(store.isReady(model()))
    }

    @Test
    fun invalidateRemovesFileAndMarker() {
        val root = dir()
        val store = ModelStore(root, FakeFetcher(payload))
        store.download(model())
        assertTrue(store.isReady(model()))

        store.invalidate(model())

        assertFalse(store.isReady(model()))
        assertFalse(File(root, "model.int8.onnx").exists())
        assertFalse(File(root, "model.int8.onnx.verified").exists())
    }

    @Test
    fun partialFileStillResumesWithoutMarker() {
        val root = dir()
        File(root, "model.int8.onnx").writeBytes(payload.copyOfRange(0, 4_000))
        val fetcher = FakeFetcher(payload)

        val result = ModelStore(root, fetcher).download(model())

        assertTrue(result.isSuccess)
        assertEquals(listOf(4_000L), fetcher.requests)
    }
}
