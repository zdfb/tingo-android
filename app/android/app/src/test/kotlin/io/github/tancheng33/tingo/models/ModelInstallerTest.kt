package io.github.tancheng33.tingo.models

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内置模型解包：模型随 APK 分发，装完就能离线推理。 */
class ModelInstallerTest {

    private fun store(): ModelStore =
        ModelStore(Files.createTempDirectory("tingo-bundled").toFile(), ThrowingFetcher)

    private fun catalog(vararg sizes: Int): ModelCatalog = ModelCatalog(
        sizes.mapIndexed { index, size ->
            ModelFile(id = "m$index", fileName = "model$index.bin", url = "https://x/y", bytes = size.toLong())
        },
    )

    private class FakeBundle(private val files: Map<String, ByteArray>) : ModelBundle {
        var opens = 0
        override fun has(name: String) = files.containsKey(name)
        override fun open(name: String): InputStream? {
            opens++
            return files[name]?.inputStream()
        }
        override fun sizeOf(name: String): Long = files[name]?.size?.toLong() ?: -1L
    }

    /** 内置包里缺文件时不该偷偷联网：这个 fetcher 一旦被调用就失败。 */
    private object ThrowingFetcher : HttpFetcher {
        override fun open(url: String, rangeStart: Long): HttpFetcher.FetchedStream =
            throw AssertionError("内置模型解包不应该联网：$url")
    }

    @Test
    fun installsEveryBundledFile() {
        val store = store()
        val catalog = catalog(16, 32)
        val bundle = FakeBundle(mapOf("model0.bin" to ByteArray(16), "model1.bin" to ByteArray(32)))

        val ok = ModelInstaller.install(store, catalog, bundle)

        assertTrue(ok)
        assertTrue(catalog.files.all { store.isReady(it) })
        assertEquals(16L, store.fileFor(catalog.files[0]).length())
    }

    @Test
    fun reportsProgressWhileInstalling() {
        val store = store()
        val catalog = catalog(64, 64)
        val bundle = FakeBundle(mapOf("model0.bin" to ByteArray(64), "model1.bin" to ByteArray(64)))
        val seen = mutableListOf<Float>()

        ModelInstaller.install(store, catalog, bundle) { seen += it }

        assertTrue("应该报进度：$seen", seen.isNotEmpty())
        assertEquals(1f, seen.last(), 0.001f)
    }

    @Test
    fun skipsFilesThatAreAlreadyInstalled() {
        val store = store()
        val catalog = catalog(16)
        val bundle = FakeBundle(mapOf("model0.bin" to ByteArray(16)))
        ModelInstaller.install(store, catalog, bundle)
        val opensAfterFirst = bundle.opens

        ModelInstaller.install(store, catalog, bundle)

        assertEquals("已是就绪状态就不该再读包", opensAfterFirst, bundle.opens)
    }

    @Test
    fun failsWhenBundleSizeDoesNotMatchManifest() {
        val store = store()
        val catalog = catalog(32)
        val bundle = FakeBundle(mapOf("model0.bin" to ByteArray(16)))

        val ok = ModelInstaller.install(store, catalog, bundle)

        assertFalse("体积对不上必须失败", ok)
        assertFalse(store.isReady(catalog.files.single()))
        assertFalse("半截文件不能留下", store.fileFor(catalog.files.single()).exists())
        assertFalse("临时文件不能留下", File(store.fileFor(catalog.files.single()).parentFile, "${catalog.files.single().fileName}.installing").exists())
    }

    @Test
    fun missingFromBundleLeavesDownloadPathOpen() {
        val store = store()
        val catalog = catalog(16)
        val bundle = FakeBundle(emptyMap())

        val ok = ModelInstaller.install(store, catalog, bundle)

        assertFalse("包里没有这个文件时不该假装成功", ok)
        assertFalse(store.isReady(catalog.files.single()))
    }
}

/** 内置解包写下的指纹必须"说实话"：只核对了字节数，不能记成 sha256。 */
class BundledMarkerTest {

    private object ThrowingFetcher : HttpFetcher {
        override fun open(url: String, rangeStart: Long): HttpFetcher.FetchedStream =
            throw AssertionError("内置解包不该联网：$url")
    }

    @Test
    fun bundledFileIsReadyWithoutPretendingDigestWasChecked() {
        val store = ModelStore(Files.createTempDirectory("tingo-marker").toFile(), ThrowingFetcher)
        val file = ModelFile(id = "m", fileName = "m.bin", url = "https://x/y", bytes = 8, sha256 = "deadbeef")
        val bundle = object : ModelBundle {
            override fun has(name: String) = true
            override fun open(name: String): InputStream = ByteArray(8).inputStream()
            override fun sizeOf(name: String): Long = 8
        }

        assertTrue(ModelInstaller.install(store, ModelCatalog(listOf(file)), bundle))

        val marker = File(store.fileFor(file).parentFile, "m.bin.verified").readText().trim()
        assertTrue("指纹不能写成 sha256：实际 $marker", marker != file.sha256)
        assertTrue("应说明是内置解包：实际 $marker", marker.contains("bundled"))
        assertTrue(store.isReady(file))
    }

    @Test
    fun digestMismatchStillInvalidates() {
        val store = ModelStore(Files.createTempDirectory("tingo-marker2").toFile(), ThrowingFetcher)
        val file = ModelFile(id = "m", fileName = "m.bin", url = "https://x/y", bytes = 8, sha256 = "deadbeef")
        store.fileFor(file).writeBytes(ByteArray(8))
        File(store.fileFor(file).parentFile, "m.bin.verified").writeText("deadbeef")

        assertTrue("摘要一致时应就绪", store.isReady(file))
        File(store.fileFor(file).parentFile, "m.bin.verified").writeText("0000")
        assertTrue("摘要不符时应作废", !store.isReady(file))
    }
}
