package io.github.tancheng33.tingo.minutes

import io.github.tancheng33.tingo.transcription.Transcript

/**
 * 纪要提示词与输出校验。
 *
 * 章节标题必须与桌面版 `summarize.rs` 一字不差——界面、推送、Obsidian 导出都靠它们识别，
 * 所以这里既生成提示词，也校验模型有没有按结构输出。
 */
object MinutesPrompt {

    /** 纪要必备的五个二级标题；「原因分析」按需出现（没分析原因就不该有鱼骨图，见 design 偏差记录）。 */
    val REQUIRED_SECTIONS = listOf("## 一句话总结", "## 议题与讨论", "## 决议", "## 待办", "## 待定问题与风险")

    const val FISHBONE_SECTION = "## 原因分析"

    private const val TEMPLATE = """下面是一场会议的语音转写，格式为「[时间戳] 说话人：内容」。

请整理成会议纪要，严格使用以下 Markdown 结构，不要输出其他内容：

# <根据内容拟一个会议标题>

## 一句话总结
<一句话>

## 议题与讨论
### 1. <议题>
- <要点>（[mm:ss]）

## 决议
- <已经拍板的事项>（[mm:ss]）

## 待办
| 事项 | 负责人 | 截止时间 | 出处 |
|---|---|---|---|
| <具体动作> | <人名或说话人> | <时间> | [mm:ss] |

## 待定问题与风险
- <没有结论的问题、提到的风险>

## 原因分析
```fishbone
{"problem": "<被分析的问题，用问句>", "branches": [{"title": "<一个原因，10 字以内>", "detail": "<一句话补充>", "refs": ["mm:ss"]}]}
```

要求：
1. 只依据转写内容，不编造。负责人或截止时间没有明确说出的，写"未明确"。
2. 转写有同音错字，结合上下文改正明显的错误；拿不准的保留原文。
3. 每条要点、决议、待办都附上出处时间戳。
4. 某一节没有内容时写"无"，不要删掉这一节（「原因分析」除外：只有会上明确分析了
   某个问题的原因时才写，否则整节省略）。
5. 去掉寒暄和口头禅，语言简洁、书面化。

转写：
{transcript}
"""

    /**
     * 拼提示词时把转写压成「[mm:ss] 说话人：内容」，与桌面版一致。
     *
     * @param maxChars 提示词里最多放多少字符；超出时截断并明确告知模型（避免它以为全文都在）
     */
    fun build(transcript: Transcript, maxChars: Int = 12_000): String {
        val body = buildString {
            transcript.sentences.forEach { sentence ->
                append('[').append(Transcript.mmss(sentence.startMs)).append("] ")
                append(transcript.speakerLabel(sentence.speaker)).append('：')
                append(sentence.text.trim()).append('\n')
            }
        }
        val clipped = if (body.length <= maxChars) {
            body
        } else {
            body.take(maxChars) + "\n（转写过长，后续内容已省略）\n"
        }
        return TEMPLATE.replace("{transcript}", clipped)
    }

    /**
     * 把提示词里的转写裁到预算以内。
     *
     * 三条硬要求，之前都踩过：
     * 1. 只裁转写这一段。提示词后面可能还挂着重试指令（[retryPrompt] 会追加），
     *    整段尾巴一刀切会把补救说明删掉，重试变成拿一模一样的提示词再跑一次。
     * 2. 裁完必须告诉用户。模型看到的"已省略"是给模型看的，用户也需要知道这份纪要
     *    只覆盖了会议的一部分，否则会把残缺的纪要当成全部。
     * 3. 装不下时不能假装成功。头部加省略标记都超预算时返回 null，由调用方如实报错，
     *    而不是把一个超上下文的提示词喂给引擎（那条路只会得到空结果）。
     */
    fun trimToTokenBudget(
        prompt: String,
        budgetTokens: Int,
        countTokens: (String) -> Int,
    ): TrimResult {
        val total = countTokens(prompt)
        if (budgetTokens <= 0 || total <= budgetTokens) return TrimResult(prompt, keptLines = 0, droppedLines = 0)

        val bodyStart = prompt.indexOf(TRANSCRIPT_HEADER)
        if (bodyStart < 0) return TrimResult(prompt, keptLines = 0, droppedLines = 0)
        val bodyFrom = bodyStart + TRANSCRIPT_HEADER.length
        // 转写块到下一个空行分隔的段落为止；重试指令在它后面，必须保住
        val tailAt = prompt.indexOf("\n\n上一次的输出不符合要求", bodyFrom)
        val bodyTo = if (tailAt >= 0) tailAt else prompt.length
        val head = prompt.substring(0, bodyFrom)
        val tail = prompt.substring(bodyTo)

        // 上一次可能已经留下过省略标记：先摘掉，避免"省略"后面还跟着内容、再跟一个省略
        val bodyLines = prompt.substring(bodyFrom, bodyTo)
            .trimEnd('\n')
            .lines()
            .filterNot { it.contains(OMITTED_MARKER.trim()) }

        var low = 0
        var high = bodyLines.size
        var best = -1
        while (low <= high) {
            val mid = (low + high) / 2
            val candidate = head + bodyLines.take(mid).joinToString("\n") + OMITTED_MARKER + tail
            if (countTokens(candidate) <= budgetTokens) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        // best = -1 表示连"头部 + 省略标记 + 尾巴"都装不下：如实返回，让调用方报错
        if (best < 0) return TrimResult(null, keptLines = 0, droppedLines = bodyLines.size)
        val kept = bodyLines.take(best)
        return TrimResult(
            prompt = head + kept.joinToString("\n") + OMITTED_MARKER + tail,
            keptLines = best,
            droppedLines = bodyLines.size - best,
        )
    }

    /** 裁剪结果：null 表示连最小提示词都装不下，调用方必须如实报错。 */
    data class TrimResult(val prompt: String?, val keptLines: Int, val droppedLines: Int) {
        val trimmed: Boolean get() = droppedLines > 0
    }

    /** 给模型看、也给用户看的省略说明。 */
    const val OMITTED_MARKER = "\n（转写过长，后续内容已省略）\n"

    /** 模板里转写块的标题，裁剪靠它定位。 */
    private const val TRANSCRIPT_HEADER = "转写：\n"

    /** 校验模型输出：该有的章节要在，鱼骨图 JSON 要能解析。 */
    fun validate(markdown: String): List<String> {
        val problems = mutableListOf<String>()
        if (!markdown.contains("# ")) problems += "缺少标题"
        REQUIRED_SECTIONS.filterNot { markdown.contains(it) }.forEach { problems += "缺少章节：$it" }
        if (markdown.contains(FISHBONE_SECTION)) {
            fishboneBodies(markdown).forEachIndexed { index, json ->
                val looksJson = json.startsWith("{") &&
                    json.contains("\"problem\"") &&
                    json.contains("\"branches\"")
                val parses = runCatching { org.json.JSONObject(json) }.isSuccess
                if (!looksJson || !parses) problems += "第 ${index + 1} 块鱼骨图不是合法 JSON"
            }
            if (fishboneBodies(markdown).isEmpty()) problems += "「原因分析」里没有 fishbone 代码块"
        }
        return problems
    }

    /** 取出所有 ```fishbone 代码块的内容。 */
    fun fishboneBodies(markdown: String): List<String> =
        Regex("```fishbone\\s*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
            .findAll(markdown)
            .map { it.groupValues[1].trim() }
            .toList()

    /** 校验不过时追加的补救指令：比第一次更明确，避免模型自己发挥。 */
    fun retryPrompt(original: String, problems: List<String>): String = buildString {
        append(original)
        append("\n\n上一次的输出不符合要求（")
        append(problems.joinToString("；"))
        append("）。请严格按上面的结构重新输出，只输出 Markdown 正文，不要解释。\n")
    }

    fun mmss(ms: Long): String = Transcript.mmss(ms)
}
