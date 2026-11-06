package io.github.tancheng33.tingo.models

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 随 APK 打包的清单必须自洽：每条都有摘要、id 不重复、总量与预期一致。
 *
 * 清单写错时下载会在用户面前失败，所以在构建期就用测试拦住。
 */
class ModelCatalogAssetTest {

    private val catalog = ModelCatalog.parse(File("src/main/assets/models.json").readText())

    @Test
    fun everyEntryHasChecksumAndReasonableSize() {
        assertTrue(catalog.files.isNotEmpty())
        catalog.files.forEach { file ->
            assertTrue("${file.id} 缺 sha256", file.sha256?.length == 64)
            assertTrue("${file.id} 体积不合理", file.bytes > 0)
            assertTrue("${file.id} 文件名不该带路径", !file.fileName.contains('/'))
        }
    }

    @Test
    fun idsAreUnique() {
        assertEquals(catalog.files.size, catalog.files.map { it.id }.toSet().size)
    }

    @Test
    fun transcriptionBundleHasTheFourModels() {
        val ids = catalog.files.map { it.id }.toSet()
        assertTrue(ids.containsAll(listOf("paraformer", "punct", "campplus", "vad")))
        // 四个模型合计 300 MB 上下：是"按需下载"而不是塞进 APK 的量级
        assertTrue(catalog.totalBytes in 300_000_000L..400_000_000L)
    }
}
