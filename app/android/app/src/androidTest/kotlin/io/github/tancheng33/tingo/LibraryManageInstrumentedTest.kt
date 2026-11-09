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

    @Test
    fun speakerNamesAndGlossaryWorkOnRealFiles() {
        val (engine, meetingsRoot) = engine()
        val dir = File(meetingsRoot, "2026-11-04_0900_周会").apply { mkdirs() }
        File(dir, "transcript.json").writeText(
            """{"source":"录音.wav","duration_ms":20000,"sentences":[
                {"speaker":0,"start_ms":0,"end_ms":3000,"text":"我是林舟"},
                {"speaker":1,"start_ms":4000,"end_ms":7000,"text":"林舟你说一下"}
            ]}""",
        )
        File(dir, "纪要.md").writeText(
            "# 周会\n\n```terms\n{\"terms\": [{\"term\": \"历史工单\", \"variants\": [\"历史中单\"]}]}\n```\n",
        )

        val before = engine.speakers("2026-11-04_0900_周会")
        assertEquals(2, before.size)
        assertEquals("说话人1", before[0].name)

        engine.setSpeakerNames("2026-11-04_0900_周会", listOf("林舟", ""))
        val after = engine.speakers("2026-11-04_0900_周会")
        assertEquals("林舟", after[0].name)
        assertEquals("空名字表示不改", "说话人2", after[1].name)

        // 提示词要能带上转写内容，模型才知道该猜谁
        val prompt = engine.speakerPrompt("2026-11-04_0900_周会", "zh")
        assertTrue(prompt.prompt.contains("我是林舟"))
        assertEquals(2, prompt.speakerCount.toInt())

        val parsed = engine.parseSpeakerSuggestions(
            "2026-11-04_0900_周会",
            """{"suggestions":[{"index":0,"name":"林舟","evidence":"[00:00] 说话人1：我是林舟"}]}""",
        )
        assertEquals("林舟", parsed[0].suggested)

        val glossary = engine.glossary()
        assertEquals(1, glossary.size)
        assertEquals("历史工单", glossary[0].term)
    }

    @Test
    fun editingMinutesKeepsHistoryAndExportsSrt() {
        val (engine, meetingsRoot) = engine()
        val dir = File(meetingsRoot, "2026-11-06_0900_周会").apply { mkdirs() }
        File(dir, "纪要.md").writeText("# 周会\n\n## 决议\n- 先修首屏\n")
        File(dir, "transcript.json").writeText(
            """{"source":"录音.wav","duration_ms":9000,"sentences":[
                {"speaker":0,"start_ms":0,"end_ms":3000,"text":"先修首屏"},
                {"speaker":1,"start_ms":4000,"end_ms":8000,"text":"好"}
            ]}""",
        )

        engine.saveMinutes("2026-11-06_0900_周会", "# 周会\n\n## 决议\n- 先修首屏\n- 再看注册页")
        val history = engine.minutesHistory("2026-11-06_0900_周会")
        assertEquals(1, history.size)
        assertTrue("历史里应当是改之前那一版：${history[0].preview}", history[0].preview.contains("先修首屏"))

        // 恢复旧版：当前那版会再进历史，所以"恢复"本身也可退回
        assertTrue(engine.minutesVersion("2026-11-06_0900_周会", history[0].id).contains("先修首屏"))
        engine.restoreMinutesVersion("2026-11-06_0900_周会", history[0].id)
        val current = File(dir, "纪要.md").readText()
        assertTrue("恢复后应当是旧版内容：$current", current.contains("先修首屏"))
        // 只断言"旧内容在"是不够的：恢复若是空操作，两个版本都含这一句，测试照样绿
        assertTrue("恢复后不该保留后加的那条待办：$current", !current.contains("再看注册页"))

        val srt = File(engine.exportSrt("2026-11-06_0900_周会", "zh"))
        assertTrue("SRT 没落盘：$srt", srt.isFile)
        val body = srt.readText()
        assertTrue("SRT 缺少序号或时间轴：$body", body.contains("-->") && body.contains("先修首屏"))
    }

    @Test
    fun exportsExcalidrawForFishboneMinutes() {
        val (engine, meetingsRoot) = engine()
        val dir = File(meetingsRoot, "2026-11-08_0900_复盘").apply { mkdirs() }
        File(dir, "纪要.md").writeText(
            """# 复盘

## 原因分析

```fishbone
{"problem":"转化率下降","branches":[{"title":"产品","detail":"首屏太慢"}]}
```
""",
        )

        val paths = engine.exportExcalidraw("2026-11-08_0900_复盘")

        assertEquals(1, paths.size)
        val body = File(paths[0]).readText()
        assertTrue("画板类型缺失：$body", body.contains("excalidraw"))
        assertTrue("问题文本没进画板", body.contains("转化率下降"))
    }

    @Test
    fun docxExportProducesARealWordPackage() {
        val dir = File(context.cacheDir, "docx-export").apply { deleteRecursively(); mkdirs() }
        val target = File(dir, "周会.docx")

        io.github.tancheng33.tingo.export.DocxWriter.write(
            markdown = "# 周会\n\n## 决议\n- 先修首屏\n",
            title = "周会",
            meta = "2026-11-08 09:00",
            target = target,
        )

        java.util.zip.ZipFile(target).use { zip ->
            assertTrue("缺正文", zip.getEntry("word/document.xml") != null)
            val doc = zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().readText()
            assertTrue("正文里没有决议：$doc", doc.contains("先修首屏"))
        }
    }

    @Test
    fun pdfExportLooksLikeAPdfWithTheContentInIt() {
        val dir = File(context.cacheDir, "pdf-export").apply { deleteRecursively(); mkdirs() }
        val target = File(dir, "周会.pdf")
        val long = buildString {
            append("# 周会\n\n## 议题与讨论\n")
            repeat(60) { append("- 第 ${it + 1} 条讨论内容：这一行故意写长一点，用来触发折行与分页\n") }
        }

        io.github.tancheng33.tingo.export.PdfWriter.write(
            markdown = long,
            title = "周会",
            meta = "2026-11-08 09:00",
            target = target,
        )

        val head = target.readBytes().take(5).toByteArray().toString(Charsets.US_ASCII)
        assertEquals("%PDF-", head)
        assertTrue("PDF 太小，可能没写进内容：${target.length()}", target.length() > 3_000)
    }
}
