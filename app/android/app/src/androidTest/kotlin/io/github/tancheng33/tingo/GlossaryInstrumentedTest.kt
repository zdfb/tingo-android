package io.github.tancheng33.tingo

import androidx.test.platform.app.InstrumentationRegistry
import io.github.tancheng33.tingo.engine.TingoEnginePort
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨会议术语表：引擎从各场纪要的 ```terms 块里汇总。
 *
 * 设置页那句"术语表（N）"完全靠这条链路：引擎读不出来，界面就永远是空的，
 * 而用户看不出这是"没有术语"还是"功能坏了"。
 */
class GlossaryInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun engine(): TingoEnginePort {
        val root = File(context.cacheDir, "glossary-test").apply { deleteRecursively(); mkdirs() }
        return TingoEnginePort.native(root.absolutePath)
    }

    private fun writeMeeting(root: String, id: String, termsJson: String) {
        // meetingsRoot() 本身就是会议库根目录（引擎已经推导好 <data>/meetings），不要再拼一层
        val dir = File(root, id)
        dir.mkdirs()
        File(dir, "纪要.md").writeText(
            """
            # 周会

            ## 议题

            讨论了排期。

            ```terms
            $termsJson
            ```
            """.trimIndent(),
        )
    }

    @Test
    fun glossaryAggregatesTermsAcrossMeetings() {
        val engine = engine()
        val root = engine.meetingsRoot()
        writeMeeting(root, "2026-10-01_1000_周一同步", """{"terms":[{"term":"星河计划","variants":["星河划计"]}]}""")
        writeMeeting(root, "2026-10-02_1000_周二同步", """{"terms":[{"term":"林舟"}]}""")

        val terms = engine.glossary()

        assertEquals(
            "两场会议的术语都要汇总出来",
            setOf("林舟", "星河计划"),
            terms.map { it.term }.toSet(),
        )
        assertTrue(
            "听错的写法也要带出来（写纪要时按正确写法纠正）",
            terms.first { it.term == "星河计划" }.variants.contains("星河划计"),
        )
    }

    @Test
    fun meetingsWithoutTermsDoNotBreakTheAggregation() {
        val engine = engine()
        val root = engine.meetingsRoot()
        writeMeeting(root, "2026-10-03_1000_没有术语", """{"terms":[]}""")

        assertTrue("没有术语时返回空表而不是报错", engine.glossary().isEmpty())
    }
}
