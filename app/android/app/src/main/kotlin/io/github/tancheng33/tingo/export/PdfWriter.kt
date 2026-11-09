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

    fun write(markdown: String, title: String, meta: String, target: File) {
        val document = PdfDocument()
        val layout = Layout()
        layout.add(title, 20f, bold = true, after = 10f)
        if (meta.isNotBlank()) layout.add(meta, 10f, color = 0xFF666666.toInt(), after = 18f)
        markdown.lines().forEach { line ->
            when {
                line.startsWith("### ") -> layout.add(line.removePrefix("### "), 12f, bold = true, before = 8f, indent = 0f)
                line.startsWith("## ") -> layout.add(line.removePrefix("## "), 14f, bold = true, before = 14f, after = 4f)
                line.startsWith("# ") -> if (line.removePrefix("# ").trim() != title) {
                    layout.add(line.removePrefix("# "), 16f, bold = true, before = 16f, after = 6f)
                }
                line.startsWith("- ") || line.startsWith("* ") ->
                    layout.add("• " + line.drop(2), 11f, indent = 12f, after = 2f)
                line.startsWith("|") -> {
                    val cells = line.trim().trim('|').split("|").map { it.trim() }
                    if (cells.all { it.matches(Regex(":?-{2,}:?")) }) return@forEach
                    layout.add(cells.joinToString("   "), 10f, indent = 8f, after = 1f)
                }
                line.isBlank() -> layout.add("", 6f, after = 0f)
                else -> layout.add(line, 11f, after = 4f)
            }
        }

        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        var y = MARGIN
        for (item in layout.items) {
            val paint = item.paint()
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
        target.outputStream().use { document.writeTo(it) }
        document.close()
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

    /** 一行待排文字：字号、粗体、缩进与上下间距。 */
    private class Item(
        val text: String,
        val size: Float,
        val bold: Boolean,
        val color: Int,
        val indent: Float,
        val before: Float,
        val after: Float,
    ) {
        fun paint(): Paint = Paint().apply {
            textSize = size
            isAntiAlias = true
            this.color = color
            typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
        }
    }

    private class Layout {
        val items = mutableListOf<Item>()

        fun add(
            text: String,
            size: Float,
            bold: Boolean = false,
            color: Int = 0xFF1D1D1F.toInt(),
            indent: Float = 0f,
            before: Float = 0f,
            after: Float = 2f,
        ) {
            items += Item(text, size, bold, color, indent, before, after)
        }
    }
}
