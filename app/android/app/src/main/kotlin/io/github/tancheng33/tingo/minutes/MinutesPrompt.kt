package io.github.tancheng33.tingo.minutes

import io.github.tancheng33.tingo.transcription.Transcript

/**
 * 纪要提示词与输出校验。
 *
 * 章节标题必须与桌面版 `summarize.rs` 一字不差——界面、推送、Obsidian 导出都靠它们识别，
 * 所以这里既生成提示词，也校验模型有没有按结构输出。
 */
object MinutesPrompt {

    /** 纪要必备的六个二级标题；「原因分析」可以省略（没分析原因就不该有鱼骨图）。 */
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
                val name = transcript.speakerNames[sentence.speaker] ?: "说话人${sentence.speaker + 1}"
                append('[').append(mmss(sentence.startMs)).append("] ")
                append(name).append('：').append(sentence.text.trim()).append('\n')
            }
        }
        val clipped = if (body.length <= maxChars) {
            body
        } else {
            body.take(maxChars) + "\n（转写过长，后续内容已省略）\n"
        }
        return TEMPLATE.replace("{transcript}", clipped)
    }

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

    fun mmss(ms: Long): String {
        val totalSeconds = ms / 1000
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }
}
