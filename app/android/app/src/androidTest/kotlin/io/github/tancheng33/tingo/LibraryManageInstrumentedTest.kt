package io.github.tancheng33.tingo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tancheng33.tingo.engine.TingoEnginePort
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 会议管理在真机（真实文件系统）上的行为：改名、标签、批量、回收站、存储统计。
 *
 * JVM 单测用的是引擎替身，验证的是"界面把动作传下去了"；这里跑真实引擎，
 * 验证"动作落到磁盘上到底是什么样"——两条都过才算接通。
 */
@RunWith(AndroidJUnit4::class)
class LibraryManageInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** 一场最小可识别的会议：有纪要即可。 */
    private fun meeting(root: File, id: String): File {
        val dir = File(root, id).apply { mkdirs() }
        File(dir, "纪要.md").writeText("# 标题\n\n## 决议\n- 先修首屏\n")
        return dir
    }

    private fun engine(): Pair<TingoEnginePort, File> {
        val root = File(context.cacheDir, "manage-test").apply { deleteRecursively(); mkdirs() }
        // 引擎把会议库固定放在 <data_root>/meetings 下
        val meetings = File(root, "meetings").apply { mkdirs() }
        return TingoEnginePort.native(root.absolutePath) to meetings
    }

    @Test
    fun renameTagsBatchAndTrashWorkOnRealFiles() {
        val (engine, meetingsRoot) = engine()
        meeting(meetingsRoot, "2026-11-01_0900_周会")
        meeting(meetingsRoot, "2026-11-02_1000_复盘")

        engine.renameMeeting("2026-11-01_0900_周会", "周会（改名）")
        val renamed = engine.scanLibrary().first { it.id == "2026-11-01_0900_周会" }
        assertEquals("周会（改名）", renamed.title)

        val tags = engine.setTags("2026-11-01_0900_周会", listOf(" 产品 ", "产品", ""))
        assertEquals(listOf("产品"), tags)

        val outcomes = engine.batch(
            listOf("2026-11-01_0900_周会", "2026-11-02_1000_复盘"),
            "archive",
            "",
        )
        assertTrue(outcomes.all { it.ok })
        assertTrue("两场都应归档", engine.scanLibrary().all { it.archived })

        val freed = engine.deleteToTrash("2026-11-02_1000_复盘")
        assertTrue(freed > 0uL)
        assertEquals(1, engine.scanLibrary().size)
        assertEquals(1, engine.listTrash().size)

        engine.restoreFromTrash("2026-11-02_1000_复盘")
        assertEquals(2, engine.scanLibrary().size)
        assertTrue(engine.listTrash().isEmpty())

        val stats = engine.storageStats()
        assertEquals(2, stats.count.toInt())
        assertTrue(stats.totalBytes > 0uL)
    }

    @Test
    fun searchFindsWordsInsideTranscriptNotJustTitles() {
        val (engine, meetingsRoot) = engine()
        val dir = meeting(meetingsRoot, "2026-11-03_1400_复盘")
        File(dir, "transcript.json").writeText(
            """{"source":"录音.wav","duration_ms":9000,"sentences":[{"speaker":0,"start_ms":2000,"end_ms":3000,"text":"短信验正码延迟四秒"}]}""",
        )

        // 引擎的模糊匹配要求词长 ≥4（3 个字的词差一个字太容易误中），
        // 所以这里用完整的"短信验证码延迟"去撞"短信验正码延迟"
        val result = engine.search("短信验证码延迟")

        assertTrue("应按识别错字模糊命中，实际：${result.hits}", result.hits.isNotEmpty())
        assertEquals("2026-11-03_1400_复盘", result.hits.first().meetingId)
    }
}
