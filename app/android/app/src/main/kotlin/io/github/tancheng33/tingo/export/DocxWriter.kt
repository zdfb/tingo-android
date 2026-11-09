package io.github.tancheng33.tingo.export

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把纪要 Markdown 写成 `.docx`。
 *
 * 桌面端是把排好版的 HTML 交给 macOS 自带的 `textutil` 转 Word；手机上没这个工具，
 * 所以这里直接写最小的 OOXML 包：正文、标题样式、项目符号、编号、表格。
 * 只写"能接着改"需要的东西——Word 打开后用户照样能改字体、加批注。
 *
 * 不追求和桌面字节一致（那边是 HTML 转的）；追求的是同样能在 Word 里打开、结构相同。
 */
object DocxWriter {

    fun write(markdown: String, title: String, meta: String, target: File) {
        val body = StringBuilder()
        body.append(paragraph(title, style = "Title"))
        if (meta.isNotBlank()) body.append(paragraph(meta, style = "Meta"))

        var index = 0
        val lines = markdown.lines()
        while (index < lines.size) {
            val line = lines[index]
            when {
                line.startsWith("```") -> {
                    // 代码块：fishbone 退化成文字，terms 直接丢掉（它是给模型的，不是给人看的）
                    val language = line.removePrefix("```").trim()
                    val block = StringBuilder()
                    index++
                    while (index < lines.size && !lines[index].startsWith("```")) {
                        block.append(lines[index]).append('\n')
                        index++
                    }
                    appendFence(body, language, block.toString())
                }

                line.startsWith("|") -> {
                    val rows = mutableListOf<String>()
                    while (index < lines.size && lines[index].startsWith("|")) {
                        rows += lines[index]
                        index++
                    }
                    body.append(table(rows))
                    continue
                }

                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length.coerceIn(1, 3)
                    val text = line.dropWhile { it == '#' }.trim()
                    // 第一个一级标题就是文档标题，已经单独写过，不再重复
                    if (!(level == 1 && text == title)) {
                        body.append(paragraph(text, style = "Heading$level"))
                    }
                }

                line.startsWith("- ") || line.startsWith("* ") -> body.append(paragraph(line.drop(2), style = "ListBullet"))
                NUMBERED.matches(line) -> body.append(paragraph(NUMBERED.replace(line, "$1"), style = "ListNumber"))
                line.isBlank() -> Unit
                else -> body.append(paragraph(line))
            }
            index++
        }

        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.put("[Content_Types].xml", CONTENT_TYPES)
            zip.put("_rels/.rels", ROOT_RELS)
            zip.put("word/document.xml", document(body.toString()))
            zip.put("word/styles.xml", STYLES)
            zip.put("word/_rels/document.xml.rels", DOCUMENT_RELS)
        }
    }

    private val NUMBERED = Regex("""^(\d+)[.)]\s+(.*)$""")

    /** fishbone 代码块退化成"问题 + 分支"的文字列表；其它代码块原样保留。 */
    private fun appendFence(body: StringBuilder, language: String, block: String) {
        val text = block.trim()
        if (text.isEmpty()) return
        when (language) {
            "terms" -> Unit
            "fishbone" -> {
                val spec = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return
                body.append(paragraph(spec.optString("problem"), style = "ListBullet"))
                val branches = spec.optJSONArray("branches")
                for (i in 0 until (branches?.length() ?: 0)) {
                    val branch = branches!!.optJSONObject(i)
                    val detail = branch?.optString("detail").orEmpty()
                    val line = if (detail.isBlank()) branch?.optString("title").orEmpty() else "${branch?.optString("title")}：$detail"
                    body.append(paragraph(line, style = "ListBullet"))
                }
            }
            else -> text.lines().forEach { body.append(paragraph(it)) }
        }
    }

    /** Markdown 表格 → Word 表格；`---` 分隔行跳过。 */
    private fun table(rows: List<String>): String {
        val cells = rows.map { row -> row.trim().trim('|').split("|").map { it.trim() } }
            .filterNot { row -> row.all { it.matches(Regex(":?-{2,}:?")) } }
        if (cells.isEmpty()) return ""
        val grid = cells.joinToString("") { row ->
            "<w:tr>" + row.joinToString("") { cell ->
                "<w:tc><w:tcPr><w:tcW w:w=\"0\" w:type=\"auto\"/></w:tcPr>" + paragraph(cell) + "</w:tc>"
            } + "</w:tr>"
        }
        return "<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"0\" w:type=\"auto\"/></w:tblPr>$grid</w:tbl>"
    }

    /** 一段文字；`**加粗**` 变成加粗的 run。 */
    private fun paragraph(text: String, style: String? = null): String {
        val runs = StringBuilder()
        var rest = text
        while (true) {
            val open = rest.indexOf("**")
            if (open < 0) {
                runs.append(run(rest))
                break
            }
            val close = rest.indexOf("**", open + 2)
            if (close < 0) {
                runs.append(run(rest))
                break
            }
            runs.append(run(rest.substring(0, open)))
            runs.append(run(rest.substring(open + 2, close), bold = true))
            rest = rest.substring(close + 2)
        }
        val props = if (style == null) "" else "<w:pPr><w:pStyle w:val=\"$style\"/></w:pPr>"
        return "<w:p>$props$runs</w:p>"
    }

    private fun run(text: String, bold: Boolean = false): String {
        if (text.isEmpty()) return ""
        val props = if (bold) "<w:rPr><w:b/></w:rPr>" else ""
        return "<w:r>$props<w:t xml:space=\"preserve\">${escape(text)}</w:t></w:r>"
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun document(body: String): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body</w:body></w:document>"""

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
</Types>"""

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    private const val DOCUMENT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    /** 只定义用到的样式：正文、标题 1–3、项目符号、编号、文档标题。 */
    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val="22"/><w:szCs w:val="22"/></w:rPr></w:rPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:styleId="Normal" w:default="1"><w:name w:val="Normal"/></w:style>
<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/>
<w:pPr><w:spacing w:after="120"/></w:pPr><w:rPr><w:b/><w:sz w:val="40"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Meta"><w:name w:val="Meta"/><w:basedOn w:val="Normal"/>
<w:pPr><w:spacing w:after="240"/></w:pPr><w:rPr><w:color w:val="555555"/><w:sz w:val="20"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/>
<w:pPr><w:keepNext/><w:spacing w:before="240" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/>
<w:pPr><w:keepNext/><w:spacing w:before="240" w:after="80"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:sz w:val="26"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:basedOn w:val="Normal"/>
<w:pPr><w:keepNext/><w:spacing w:before="200" w:after="60"/><w:outlineLvl w:val="2"/></w:pPr><w:rPr><w:b/><w:sz w:val="24"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="ListBullet"><w:name w:val="List Bullet"/><w:basedOn w:val="Normal"/>
<w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr><w:spacing w:after="40"/></w:pPr></w:style>
<w:style w:type="paragraph" w:styleId="ListNumber"><w:name w:val="List Number"/><w:basedOn w:val="Normal"/>
<w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="2"/></w:numPr><w:spacing w:after="40"/></w:pPr></w:style>
<w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/><w:tblPr><w:tblBorders>
<w:top w:val="single" w:sz="4" w:color="BBBBBB"/><w:left w:val="single" w:sz="4" w:color="BBBBBB"/>
<w:bottom w:val="single" w:sz="4" w:color="BBBBBB"/><w:right w:val="single" w:sz="4" w:color="BBBBBB"/>
<w:insideH w:val="single" w:sz="4" w:color="BBBBBB"/><w:insideV w:val="single" w:sz="4" w:color="BBBBBB"/>
</w:tblBorders></w:tblPr></w:style>
</w:styles>"""
}
