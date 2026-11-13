package io.github.tancheng33.tingo.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF 的排版逻辑（Markdown → 待排行）是纯函数，可以在 JVM 上直接验。
 *
 * 最要紧的一条：不能把 `terms` / `fishbone` 的 JSON 原样印到纸上——
 * 那是给模型的元数据，不是给人看的内容。`.docx` 已经这么处理，PDF 不能两样。
 */
class PdfWriterTest {
    @Test
    fun fishboneProducesDiagramAndPreservesBranchDetails() {
        val items = rows("```fishbone\n{\"problem\":\"延迟\",\"branches\":[{\"title\":\"网络\",\"detail\":\"带宽不足\"}]}\n```")
        assertTrue(items.any { it.diagram != null })
        assertTrue(items.any { it.text.contains("带宽不足") })
    }

    private fun rows(markdown: String, title: String = "周会") = PdfWriter.rows(markdown, title, "2026-11-08 09:00")

    private fun text(markdown: String): String = rows(markdown).joinToString("\n") { it.text }

    @Test
    fun dropsTermsBlockAndDegradesFishbone() {
        val body = text(
            """
            # 周会

            ## 原因分析

            ```fishbone
            {"problem":"转化率下降","branches":[{"title":"产品","detail":"首屏太慢"}]}
            ```

            ```terms
            {"terms":[{"term":"历史工单"}]}
            ```
            """.trimIndent(),
        )

        assertTrue("问题要保留：$body", body.contains("转化率下降"))
        assertTrue("分支要保留：$body", body.contains("首屏太慢"))
        assertTrue("鱼骨图 JSON 不该印出来", !body.contains("{\"problem\""))
        assertTrue("术语表 JSON 不该印出来", !body.contains("{\"terms\""))
    }

    @Test
    fun stripsInlineBoldMarkersInsteadOfPrintingAsterisks() {
        val body = text("## 决议\n\n- 首屏 **太慢**\n")

        assertTrue("正文要在：$body", body.contains("首屏 太慢"))
        assertTrue("星号不该印在纸上：$body", !body.contains("**"))
    }

    @Test
    fun titleIsNotRepeatedInTheBody() {
        val rows = rows("# 周会\n\n## 决议\n- 先修首屏\n")

        assertEquals("标题只出现一次", 1, rows.count { it.text == "周会" })
        assertTrue("二级标题要加粗", rows.any { it.text == "决议" && it.bold })
    }

    @Test
    fun tableSeparatorRowIsSkipped() {
        val rows = text("## 待办\n| 谁 | 做什么 |\n| --- | --- |\n| 林舟 | 修首屏 |\n")

        assertTrue("表头要在", rows.contains("谁"))
        assertTrue("数据行要在", rows.contains("修首屏"))
        assertTrue("分隔行不该印出来：$rows", !rows.contains("---"))
    }
}
