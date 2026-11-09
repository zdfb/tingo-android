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

        val vm = SettingsViewModel(
            store = store,
            catalog = oneFile,
            settings = io.github.tancheng33.tingo.settings.AppSettings(io.github.tancheng33.tingo.settings.SharedPrefsStore(context)),
            meetingsRoot = File(context.cacheDir, "models-settings-meetings"),
        )
        // 走 ViewModel 自己的路径：等它统计完、再让它删除，断言状态与磁盘都归零
        awaitState(vm) { !it.loading && it.asr.bytes == before }
        assertTrue("统计到的占用应与文件一致", vm.state.value.asr.isReady)

        vm.deleteAsr()
        awaitState(vm) { it.asr.isEmpty }

        assertEquals(0L, store.usedBytes(oneFile))
        assertTrue("文件应该被删掉", !onDisk.isFile)
    }

    /** 轮询等待 ViewModel 在 IO 线程上算完；超时就失败，避免测试空转。 */
    private fun awaitState(
        vm: SettingsViewModel,
        timeoutMs: Long = 10_000,
        predicate: (SettingsUiState) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate(vm.state.value)) return
            Thread.sleep(50)
        }
        throw AssertionError("等待状态超时，当前：${vm.state.value}")
    }

    @Test
    fun hotwordsSurviveARoundTripThroughTheEngineConfig() {
        val root = File(context.filesDir, "hotwords-test").apply { deleteRecursively(); mkdirs() }
        val engine = io.github.tancheng33.tingo.engine.TingoEnginePort.native(root.absolutePath)

        val saved = engine.setHotwords(listOf(" 星河计划 ", "Kafka", "星河计划", ""))

        assertEquals(listOf("星河计划", "Kafka"), saved)
        assertEquals(saved, engine.hotwords())
        // 提示词里的那段说明也要跟着热词走（与桌面同一份文案）
        val glossary = engine.minutesGlossary("zh")
        assertTrue("专有名词段里要有热词：$glossary", glossary.contains("星河计划"))
        // 真的写到了磁盘上的配置文件，重启后还在
        val reopened = io.github.tancheng33.tingo.engine.TingoEnginePort.native(root.absolutePath)
        assertEquals(saved, reopened.hotwords())
    }
}
