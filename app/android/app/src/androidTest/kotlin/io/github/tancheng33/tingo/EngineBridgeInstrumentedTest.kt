package io.github.tancheng33.tingo

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tancheng33.tingo.engine.FailureKind
import io.github.tancheng33.tingo.engine.SectionKind
import io.github.tancheng33.tingo.engine.TingoEnginePort
import io.github.tancheng33.tingo.engine.TingoFailure
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 桥接层的设备级验证：真实 .so + 真实引擎 + 真实文件系统。
 *
 * 这里用桌面版 v0.5.2 的真实产出做夹具，所以它同时验证了「手机能直接读电脑录的会议」
 * 这条兼容性要求，而不只是"JSON 能解析"。
 */
@RunWith(AndroidJUnit4::class)
class EngineBridgeInstrumentedTest {

    private lateinit var root: File
    private lateinit var engine: TingoEnginePort

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.getExternalFilesDir(null), "tingo")
        copyFixtures(context)
        engine = TingoEnginePort.native(root.absolutePath)
    }

    @Test
    fun engineReportsVersionAndCapabilities() {
        assertTrue(engine.engineVersion().isNotBlank())
        val caps = engine.capabilities()
        assertTrue(caps.meetingStorage)
        assertTrue(caps.transcriptParse)
        assertTrue(caps.minutesParse)
        assertTrue(caps.fishboneParse)
        assertTrue(caps.speakerNaming)
        assertTrue(caps.termsExtract)
    }

    @Test
    fun scansDesktopMeetingsSortedNewestFirst() {
        val meetings = engine.scanLibrary().map { it.id }
        assertEquals(
            listOf("2026-10-28_1430_官网注册转化复盘", "2026-10-27_0930_Website_registration_post-mortem"),
            meetings,
        )
    }

    @Test
    fun readsChineseMeetingWithAliasesAndMinutes() {
        val detail = engine.readMeeting("2026-10-28_1430_官网注册转化复盘")

        assertNotNull(detail.transcript)
        val transcript = detail.transcript!!
        assertEquals(4, transcript.aliases.size)
        assertTrue(transcript.turns.isNotEmpty())
        assertEquals("林舟", transcript.turns.first().speakerName)
        assertTrue(transcript.durationMs > 0uL)

        assertNotNull(detail.minutesText)
        val minutesText = detail.minutesText!!
        val doc = engine.parseMinutes(minutesText)
        // 纪要里的 H1 与 meta.json 的标题可以不同（用户改过标题），这里只要求非空且与章节一致
        assertTrue(doc.title.isNotBlank())
        assertTrue(doc.sections.any { it.kind == SectionKind.ONE_LINER })
        assertTrue(doc.sections.any { it.kind == SectionKind.ROOT_CAUSE })

        assertNotNull(detail.fishbone)
        val fishbone = detail.fishbone!!
        assertTrue(fishbone.problem.isNotBlank())
        assertTrue(fishbone.branches.isNotEmpty())
    }

    @Test
    fun readsEnglishMeetingWithoutAudio() {
        val detail = engine.readMeeting("2026-10-27_0930_Website_registration_post-mortem")
        assertTrue(!detail.summary.hasAudio)
        val doc = engine.parseMinutes(detail.minutesText!!)
        assertTrue(doc.sections.any { it.kind == SectionKind.ONE_LINER })
        assertTrue(doc.sections.any { it.kind == SectionKind.ACTIONS })
    }

    @Test
    fun corruptedTranscriptIsListedAsUnreadable() {
        val broken = File(File(root, "meetings"), "2026-11-01_0900_Broken")
        broken.mkdirs()
        File(broken, "transcript.json").writeText("{not json")

        val entry = engine.scanLibrary().single { it.id == "2026-11-01_0900_Broken" }
        assertTrue(entry.unreadable)

        val failure = runCatching { engine.readMeeting("2026-11-01_0900_Broken") }.exceptionOrNull()
        assertTrue(failure is TingoFailure)
        assertEquals(FailureKind.MALFORMED, (failure as TingoFailure).kind)
    }

    @Test
    fun scanningDoesNotTouchMeetingFiles() {
        // 只读浏览：扫描不能让用户的会议文件发生任何变化
        val transcript = File(
            File(File(root, "meetings"), "2026-10-28_1430_官网注册转化复盘"),
            "transcript.json",
        )
        val before = transcript.readBytes().contentHashCode()

        repeat(3) { engine.scanLibrary() }

        assertEquals(before, transcript.readBytes().contentHashCode())
    }

    @Test
    fun unknownMeetingIdIsReportedAsMissing() {
        val failure = runCatching { engine.readMeeting("2020-01-01_0000_不存在") }.exceptionOrNull()
        assertEquals(FailureKind.MISSING, (failure as TingoFailure).kind)
    }

    /** 把 androidTest 里的夹具复制进被测应用的会议库；文件属主是应用自己，引擎才读得到。 */
    @Test
    fun archivesAndDeletesAMeetingThroughTheBridge() {
        val meetings = File(root, "meetings")
        val id = "2026-11-01_0900_临时会议"
        val dir = File(meetings, id)
        dir.mkdirs()
        File(dir, "纪要.md").writeText("# 临时会议\n\n## 决议\n- 先这样\n")

        engine.setArchived(id, true)
        assertTrue("归档没有写进 meta.json", File(dir, "meta.json").readText().contains("archived"))

        val freed = engine.deleteMeeting(id)
        assertTrue("删除后目录还在", !dir.exists())
        assertTrue("释放字节数应为正", freed > 0uL)
        assertTrue("删除后会议库里不该再有它", engine.scanLibrary().none { it.id == id })
    }

    @Test
    fun deleteAlsoRejectsPathTraversal() {
        val failure = runCatching { engine.deleteMeeting("../etc") }.exceptionOrNull()
        assertTrue("删除必须和归档一样拒绝越界 id", failure != null)
    }

    @Test
    fun archivingUnknownMeetingFails() {
        val failure = runCatching { engine.setArchived("2020-01-01_0000_不存在", true) }.exceptionOrNull()
        assertTrue("应当报错而不是静默成功", failure != null)
    }

    /**
     * 从干净的会议库开始。
     *
     * 实机上的应用数据不会被自动清掉（`adb install -r` 保留数据、Gradle 也可能跳过卸载），
     * 上一次运行留下的目录会让"库里有哪几场会议"这种断言假失败——模拟器上没暴露，
     * 因为每次重装都清了数据。
     */
    private fun copyFixtures(context: Context) {
        val meetings = File(root, "meetings")
        meetings.listFiles()?.forEach { it.deleteRecursively() }
        // 夹具随测试 APK 打包，因此从 instrumentation 的 context 取 assets，写入 targetContext 的目录
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for (name in assets.list("fixtures").orEmpty()) {
            val target = File(meetings, name)
            target.mkdirs()
            for (file in assets.list("fixtures/$name").orEmpty()) {
                assets.open("fixtures/$name/$file").use { input ->
                    File(target, file).outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}
