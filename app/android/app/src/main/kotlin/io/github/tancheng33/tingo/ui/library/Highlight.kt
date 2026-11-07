package io.github.tancheng33.tingo.ui.library

/**
 * 搜索命中的高亮切分。
 *
 * 只做一件事：把"文本 + 关键词"切成"要不要强调"的若干段，界面照着画就行。
 * 放在这里而不是 Composable 里，是因为切分规则值得被测试（大小写、多次命中、
 * 关键词为空、关键词比文本还长这些边界都不该靠肉眼确认）。
 */
object Highlight {

    /** @param text 这一段是命中片段 */
    data class Piece(val text: String, val matched: Boolean)

    /**
     * @param needle 关键词；空白关键词表示不高亮
     */
    fun split(text: String, needle: String): List<Piece> {
        val trimmed = needle.trim()
        if (trimmed.isEmpty() || text.isEmpty()) return listOf(Piece(text, false))

        val pieces = mutableListOf<Piece>()
        var cursor = 0
        while (cursor < text.length) {
            val hit = text.indexOf(trimmed, cursor, ignoreCase = true)
            if (hit < 0) {
                pieces += Piece(text.substring(cursor), false)
                break
            }
            if (hit > cursor) pieces += Piece(text.substring(cursor, hit), false)
            pieces += Piece(text.substring(hit, hit + trimmed.length), true)
            cursor = hit + trimmed.length
        }
        return pieces.filter { it.text.isNotEmpty() }
    }
}
