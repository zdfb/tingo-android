package io.github.tancheng33.tingo.ui.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tancheng33.tingo.models.ModelCatalog
import io.github.tancheng33.tingo.testing.ModelDownloads
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设置页的模型占用在真机/模拟器上也算得对、删得掉。
 *
 * 单测用的是临时目录里的假文件；这里用真实下载下来的模型文件走一遍，
 * 顺带证明"删除后磁盘上真的少了这么多字节"。
 */
@RunWith(AndroidJUnit4::class)
class SettingsInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun usageReflectsRealFilesAndDeleteFreesThem() = runBlocking {
        val full = ModelDownloads.catalog(context)
        val vad = full.byId("vad")!!
        val store = ModelDownloads.store(context, "models-settings")
        ModelDownloads.ensure(store, listOf(vad))

        // 只把 vad 这一项交给设置页，避免把 300 多兆的其它模型也删掉
        val oneFile = ModelCatalog(listOf(vad))
        val before = store.usedBytes(oneFile)
        assertEquals(vad.bytes, before)

        val onDisk = File(context.cacheDir, "models-settings/${vad.fileName}")
        assertTrue("模型文件应该真的落盘了", onDisk.isFile)

        val vm = SettingsViewModel(store = store, catalog = oneFile, llmCatalog = ModelCatalog(listOf()))
        val freed = store.deleteAll(oneFile)

        assertEquals("删除应释放刚刚统计到的字节", before, freed)
        assertEquals(0L, store.usedBytes(oneFile))
        assertTrue("文件应该被删掉", !onDisk.isFile)
        vm.deleteAsr()
    }
}
