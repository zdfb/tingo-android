package io.github.tancheng33.tingo.export

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File

/**
 * 把纪要 Markdown 排成 A4 PDF。
 *
 * 桌面端是"排一份打印用 HTML 交给系统 WebKit 打印"；手机上没有那条路，
 * 这里用系统的 `PdfDocument` 直接画：A4 版面、标题分级、要点缩进、表格按行排。
 * 版式不追求和桌面逐像素一致，追求"打印出来能读、文字能选中"。
 */
object PdfWriter {

    private const val PAGE_WIDTH = 595 // A4 @72dpi
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 48f
    private val CONTENT_WIDTH = PAGE_WIDTH - MARGIN * 2

    /** 一行待排文字：纯数据，排版逻辑（Markdown → 行）与绘制分开，便于单测。 */
    internal data class Row(
        val text: String,
        val size: Float,
        val bold: Boolean = false,
        val muted: Boolean = false,
        val indent: Float = 0f,
        val before: Float = 0f,
        val after: Float = 2f,
    )

    /**
     * Markdown → 待排行。
     *
     * 代码块按 `.docx` 同样的取舍：`terms` 丢掉（给模型的，不是给人看的）、
     * `fishbone` 退化成"问题 + 分支"的文字；行内加粗只保留文字，不把星号印到纸上。
     */
    internal fun rows(markdown: String, title: String, meta: String): List<Row> {
        val out = mutableListOf<Row>()
        out += Row(title, 20f, bold = true, after = 10f)
        if (meta.isNotBlank()) out += Row(meta, 10f, muted = true, after = 18f)
        val lines = markdown.lines()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.startsWith("```")) {
                val language = line.removePrefix("```").trim()
                val block = StringBuilder()
                index++
                while (index < lines.size && !lines[index].startsWith("```")) {
                    block.append(lines[index]).append('\n')
                    index++
                }
                out += fenceRows(language, block.toString())
                index++
                continue
            }
            when {
                line.startsWith("### ") -> out += Row(line.removePrefix("### "), 12f, bold = true, before = 8f)
                line.startsWith("## ") -> out += Row(line.removePrefix("## "), 14f, bold = true, before = 14f, after = 4f)
                line.startsWith("# ") -> if (line.removePrefix("# ").trim() != title) {
                    out += Row(line.removePrefix("# "), 16f, bold = true, before = 16f, after = 6f)
                }
                line.startsWith("- ") || line.startsWith("* ") ->
                    out += Row("• " + plain(line.drop(2)), 11f, indent = 12f, after = 2f)
                line.startsWith("|") -> {
                    val cells = line.trim().trim('|').split("|").map { it.trim() }
                    if (!cells.all { it.matches(Regex(":?-{2,}:?")) }) {
                        out += Row(cells.joinToString("   "), 10f, indent = 8f, after = 1f)
                    }
                }
                line.isBlank() -> out += Row("", 6f, after = 0f)
                else -> out += Row(plain(line), 11f, after = 4f)
            }
            index++
        }
        return out
    }

    /** 代码块的排法；`terms` 丢弃，`fishbone` 退化成文字，其余原样。 */
    private fun fenceRows(language: String, block: String): List<Row> {
        val text = block.trim()
        if (text.isEmpty()) return emptyList()
        return when (language) {
            "terms" -> emptyList()
            "fishbone" -> {
                val spec = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return emptyList()
                val rows = mutableListOf(Row(spec.optString("problem"), 11f, bold = true, after = 2f))
                val branches = spec.optJSONArray("branches")
                for (i in 0 until (branches?.length() ?: 0)) {
                    val branch = branches!!.optJSONObject(i)
                    val detail = branch?.optString("detail").orEmpty()
                    val line = if (detail.isBlank()) {
                        branch?.optString("title").orEmpty()
                    } else {
                        "${branch?.optString("title")}：$detail"
                    }
                    rows += Row("• $line", 11f, indent = 12f, after = 1f)
                }
                rows
            }
            else -> text.lines().map { Row(it, 10f, indent = 8f, after = 1f) }
        }
    }

    /** 去掉行内加粗标记：PDF 里用同字号，星号不该印在纸上。 */
    private fun plain(text: String): String = text.replace("**", "")

    fun write(markdown: String, title: String, meta: String, target: File) {
        val document = PdfDocument()
        val rows = rows(markdown, title, meta)

        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        var y = MARGIN
        for (item in rows) {
            val paint = paintFor(item)
            val lines = wrap(item.text, paint, CONTENT_WIDTH - item.indent)
            val needed = item.before + lines.size * paint.textSize * 1.5f + item.after
            if (y + needed > PAGE_HEIGHT - MARGIN) {
                document.finishPage(page)
                pageNumber++
                page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
                y = MARGIN
            }
            y += item.before
            lines.forEach { text ->
                y += paint.textSize
                if (text.isNotEmpty()) page.canvas.drawText(text, MARGIN + item.indent, y, paint)
                y += paint.textSize * 0.5f
            }
            y += item.after
        }
        document.finishPage(page)
        target.parentFile?.mkdirs()
        try {
            target.outputStream().use { document.writeTo(it) }
        } finally {
            // 原生文档不释放会一直占内存；中途抛异常时也要关
            document.close()
        }
    }

    /** 按可用宽度折行；中文没有空格，逐字量宽度。 */
    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return listOf("")
        val lines = mutableListOf<String>()
        val buffer = StringBuilder()
        var width = 0f
        for (ch in text) {
            val charWidth = paint.measureText(ch.toString())
            if (width + charWidth > maxWidth && buffer.isNotEmpty()) {
                lines += buffer.toString()
                buffer.clear()
                width = 0f
            }
            buffer.append(ch)
            width += charWidth
        }
        if (buffer.isNotEmpty()) lines += buffer.toString()
        return lines
    }

    /** 行样式 → 画笔：粗体、正文色与次要色。 */
    private fun paintFor(row: Row): Paint = Paint().apply {
        textSize = row.size
        isAntiAlias = true
        color = if (row.muted) 0xFF666666.toInt() else 0xFF1D1D1F.toInt()
        typeface = if (row.bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
    }
}
