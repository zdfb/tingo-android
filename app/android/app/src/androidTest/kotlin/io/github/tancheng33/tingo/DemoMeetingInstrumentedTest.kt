package io.github.tancheng33.tingo

import androidx.test.platform.app.InstrumentationRegistry
import io.github.tancheng33.tingo.engine.TingoEnginePort
import io.github.tancheng33.tingo.importing.DemoMeeting
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 示例会议：assets 里的素材要真的装进会议库，并且能被扫到、能读出纪要。
 *
 * 这是新手引导的最后一步——装不出来，用户第一次打开就看不到 Tingo 产出什么。
 */
class DemoMeetingInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun engine(): Pair<TingoEnginePort, File> {
        val root = File(context.cacheDir, "demo-test").apply { deleteRecursively(); mkdirs() }
        return TingoEnginePort.native(root.absolutePath) to root
    }

    @Test
    fun sampleMeetingInstallsFromAssetsAndIsReadable() {
        val (engine, _) = engine()
        val demo = DemoMeeting(engine, context)

        val id = demo.install(english = false)

        assertTrue("示例会议该装上：$id", id?.contains("示例") == true)
        val meetingId = id!!
        val summary = engine.scanLibrary().firstOrNull { it.id == meetingId }
        assertTrue("扫库要能看见示例会议", summary != null)
        val detail = engine.readMeeting(meetingId)
        assertTrue("示例会议要有纪要正文", detail.minutesText?.contains("##") == true)
        // 引擎侧的 Transcript 以 turns（每段话+时间戳）为结构，不是界面那个同名类型
        assertTrue("示例会议要有转写（时间戳能跳转）", detail.transcript?.turns?.isNotEmpty() == true)
        // 素材里的音频也要在：否则示例会议点播放是空的（扫描结果直接告诉我们）
        assertTrue("示例会议缺音频", summary!!.hasAudio)
    }

    @Test
    fun installingTwiceDoesNotDuplicateTheSample() {
        val (engine, _) = engine()
        val demo = DemoMeeting(engine, context)

        val first = demo.install(english = false)
        val second = demo.install(english = false)

        assertEquals(first, second)
        val samples = engine.scanLibrary().filter { it.id.contains("示例") }
        assertEquals("示例会议不该出现两份", 1, samples.size)
    }

    @Test
    fun englishSampleUsesEnglishMinutes() {
        val (engine, _) = engine()
        val demo = DemoMeeting(engine, context)

        val id = demo.install(english = true)!!

        val detail = engine.readMeeting(id!!)
        val minutes = detail.minutesText.orEmpty()
        assertTrue("英文界面要装英文纪要：${minutes.take(40)}", minutes.contains("Summary"))
    }
}
