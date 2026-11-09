package io.github.tancheng33.tingo.export

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Word 导出：桌面端把排好版的 HTML 交给 macOS 的 `textutil` 转 `.docx`，
 * 手机上没有这个工具，只能自己写 OOXML。这里盯的是"生成的包能被 Word 认、
 * 章节与要点都在对的位置"——不是"字节和桌面一样"（那边根本是另一条实现）。
 */
class DocxWriterTest {

    private val minutes = """
        # 产品复盘

        ## 一句话总结
        注册转化率下降。

        ## 议题与讨论
        - 首屏 **太慢**
        - 埋点缺失

        ## 决议
        1. 先修首屏
        2. 再补埋点

        ## 原因分析

        ```fishbone
        {"problem":"转化率下降","branches":[{"title":"产品","detail":"首屏太慢"}]}
        ```

        ## 待办
        | 谁 | 做什么 |
        | --- | --- |
        | 林舟 | 修首屏 |
    """.trimIndent()

    private fun write(minutes: String = this.minutes, title: String = "产品复盘"): File {
        val target = File.createTempFile("minutes", ".docx").apply { deleteOnExit() }
        DocxWriter.write(markdown = minutes, title = title, meta = "2026-11-07 10:00 · 12:30", target = target)
        return target
    }

    private fun entry(file: File, name: String): String = ZipFile(file).use { zip ->
        zip.getInputStream(zip.getEntry(name)).bufferedReader().readText()
    }

    @Test
    fun writesAPackageWordCanOpen() {
        val docx = write()

        val names = ZipFile(docx).use { zip -> zip.entries().toList().map { it.name } }
        assertTrue("缺少 content types：$names", names.contains("[Content_Types].xml"))
        assertTrue("缺少根关系：$names", names.contains("_rels/.rels"))
        assertTrue("缺少正文：$names", names.contains("word/document.xml"))
        assertTrue("缺少样式：$names", names.contains("word/styles.xml"))
        val types = entry(docx, "[Content_Types].xml")
        assertTrue("Word 需要 document 的 content type", types.contains("wordprocessingml.document.main+xml"))
    }

    @Test
    fun keepsHeadingsBulletsAndTables() {
        val doc = entry(write(), "word/document.xml")

        // 文档标题单独排在开头（与桌面 `skipHead` 一致），正文里的一级标题不再重复
        assertTrue("标题没进正文：$doc", doc.contains("产品复盘"))
        assertEquals("标题只该出现一次", 1, Regex("产品复盘").findAll(doc).count())
        assertTrue("标题没用 Title 样式：$doc", doc.contains("w:val=\"Title\""))
        // 各级标题用样式而不是"手调字号"：Word 的导航窗格靠 pStyle
        assertTrue("二级标题没套样式：$doc", doc.contains("Heading2"))
        assertTrue("要点没做成列表", doc.contains("ListBullet"))
        assertTrue("表格没生成：$doc", doc.contains("<w:tbl>"))
        assertTrue("表格内容缺失：$doc", doc.contains("修首屏"))
    }

    @Test
    fun turnsFishboneCodeBlockIntoReadableText() {
        val doc = entry(write(), "word/document.xml")

        // Word 里画不了鱼骨图：按桌面的做法退化成文字
        assertTrue("问题没写进去：$doc", doc.contains("转化率下降"))
        assertTrue("分支没写进去：$doc", doc.contains("首屏太慢"))
        assertTrue("不该把 JSON 原样塞进 Word：$doc", !doc.contains("{\"problem\""))
    }

    @Test
    fun dropsTermsBlockInsteadOfDumpingJson() {
        val withTerms = minutes + "\n```terms\n{\"terms\":[{\"term\":\"历史工单\"}]}\n```\n"
        val doc = entry(write(withTerms), "word/document.xml")

        assertTrue("术语表 JSON 不该出现在正文：$doc", !doc.contains("\"terms\""))
    }

    @Test
    fun escapesXmlSignaturesInsteadOfBreaking() {
        val doc = entry(write("# A < B & C\n\nx < y > z\n"), "word/document.xml")

        assertTrue("尖括号要转义：$doc", doc.contains("A &lt; B &amp; C"))
        assertTrue("正文也要转义", doc.contains("x &lt; y &gt; z"))
    }

    @Test
    fun numberedListsKeepTheirTextAndReferenceRealNumbering() {
        val docx = write()
        val doc = entry(docx, "word/document.xml")
        val names = ZipFile(docx).use { zip -> zip.entries().toList().map { it.name } }

        // 编号列表的正文不能被序号替换掉（曾经 `replace("$1")` 把整句吃了）
        assertTrue("编号列表丢了正文：$doc", doc.contains("先修首屏") && doc.contains("再补埋点"))
        // 样式里引用 numId，就必须真的写出 numbering.xml，否则 Word 里没有符号
        assertTrue("缺 numbering.xml：$names", names.contains("word/numbering.xml"))
        val numbering = entry(docx, "word/numbering.xml")
        assertTrue("编号定义里没有 decimal：$numbering", numbering.contains("decimal"))
        assertTrue("项目符号定义缺失", numbering.contains("bullet"))
    }

    @Test
    fun inlineBoldBecomesARunWithBoldFlag() {
        val doc = entry(write("## 决议\n\n- 首屏 **太慢**\n"), "word/document.xml")

        assertTrue("加粗没生效：$doc", doc.contains("<w:b/>") || doc.contains("<w:b />"))
        assertEquals("加粗不该留在正文里", false, doc.contains("**太慢**"))
    }
}
