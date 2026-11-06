package io.github.tancheng33.tingo.transcription

/**
 * 把一段带标点的文本按句末标点切成多句，并把时间戳按字符长度分摊到每句。
 *
 * 为什么需要：VAD 切出来的是"一段话"，标点模型会在段内补出句号。规格要求
 * "一句话一个 sentence、句子边界与时间戳边界一致"，所以段内多句必须各自有
 * 自己的 [startMs, endMs]——否则点击某句话会跳到段落开头，对不上原话。
 *
 * 分摊按字符数加权：中文口播的字数与时长近似成正比，这比平均分配更接近实际。
 */
object SentenceSplitter {

    /** 句末标点；逗号、顿号不算断句。 */
    private val SENTENCE_END = charArrayOf('。', '！', '？', '；', '!', '?', ';')

    /**
     * @param text 已加标点的整段文本
     * @param startMs 段落起始时间
     * @param endMs 段落结束时间
     * @return 至少一句；空白文本返回空列表
     */
    fun split(text: String, startMs: Long, endMs: Long): List<TranscriptSentence> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        val pieces = cut(trimmed)
        if (pieces.size == 1) {
            return listOf(TranscriptSentence(speaker = 0, startMs = startMs, endMs = maxOf(endMs, startMs), text = pieces.single()))
        }

        val totalChars = pieces.sumOf { it.length }.coerceAtLeast(1)
        val span = (endMs - startMs).coerceAtLeast(pieces.size.toLong())
        var consumed = 0
        return pieces.mapIndexed { index, piece ->
            val before = consumed
            consumed += piece.length
            val from = startMs + span * before / totalChars
            // 最后一句一定收在段落末尾，避免舍入把尾巴丢掉
            val to = if (index == pieces.lastIndex) maxOf(endMs, from) else startMs + span * consumed / totalChars
            TranscriptSentence(speaker = 0, startMs = from, endMs = maxOf(to, from), text = piece)
        }
    }

    /** 按句末标点切分，标点留在前半句上；连续标点（"？！"）不额外断句。 */
    private fun cut(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            current.append(ch)
            index++
            if (ch !in SENTENCE_END) continue
            // "？！" 这类连着的标点属于同一句的结尾，一起吞掉
            while (index < text.length && text[index] in SENTENCE_END) {
                current.append(text[index])
                index++
            }
            result += current.toString().trim()
            current.setLength(0)
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) result += tail
        return result.filter { it.isNotEmpty() }
    }
}
