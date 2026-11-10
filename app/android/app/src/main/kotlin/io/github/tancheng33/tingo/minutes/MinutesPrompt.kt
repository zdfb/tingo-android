package io.github.tancheng33.tingo.minutes

import io.github.tancheng33.tingo.transcription.Transcript

/**
 * 纪要提示词与输出校验。
 *
 * 章节标题必须与桌面版 `summarize.rs` 一字不差——界面、推送、Obsidian 导出都靠它们识别，
 * 所以这里既生成提示词，也校验模型有没有按结构输出。
 */
object MinutesPrompt {

    /**
     * 纪要必备的五个二级标题；原因分析按需出现（没分析原因就不该有鱼骨图，见 design 偏差记录）。
     *
     * 中英两套与桌面 `minutes.rs` 的解析规则、`summarize.rs` 的提示词一一对应——
     * 标题对不上任何一边，界面就会把整篇当成"没有章节"的纯文本。
     */
    val REQUIRED_SECTIONS = listOf("## 一句话总结", "## 议题与讨论", "## 决议", "## 待办", "## 待定问题与风险")
    val REQUIRED_SECTIONS_EN = listOf("## Summary", "## Discussion", "## Decisions", "## Action Items", "## Open Questions & Risks")

    const val FISHBONE_SECTION = "## 原因分析"
    const val FISHBONE_SECTION_EN = "## Root Cause Analysis"

    private const val TEMPLATE_ZH = """下面是一场会议的语音转写，格式为「[时间戳] 说话人：内容」。

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
{glossary}
转写：
{transcript}

以上是全部转写内容（到此为止，后面没有更多转写）。现在请按要求输出会议纪要。
"""

    /**
     * 英文提示词：与桌面 `MINUTES_PROMPT_EN` 同一份要求（章节标题必须一致）。
     *
     * 只在"这次纪要要写英文"时使用；中文会议选英文纪要（桌面允许）时，
     * 转写正文是中文、模板是英文，模型按模板的语言输出。
     */
    private const val TEMPLATE_EN = """Below is the speech-recognition transcript of a meeting, one line per turn in the form "[timestamp] Speaker: content".

Write the meeting minutes using exactly the following Markdown structure, and output nothing else:

# <a short title>

## Summary
<one sentence>

## Discussion
### 1. <topic>
- <key point> ([mm:ss])

## Decisions
- <what was decided> ([mm:ss])

## Action Items
| Item | Owner | Due | Source |
|---|---|---|---|
| <concrete action> | <name or speaker> | <date> | [mm:ss] |

## Open Questions & Risks
- <unresolved questions, risks raised>

## Root Cause Analysis
```fishbone
{"problem": "<the problem being analyzed, as a question>", "branches": [{"title": "<one cause, 5 words max>", "detail": "<one-sentence elaboration>", "refs": ["mm:ss"]}]}
```

Rules:
1. Use only what is in the transcript; do not make anything up. If an owner or a due date was not stated explicitly, write "Not specified".
2. The transcript has recognition errors (homophones, misheard words). Fix obvious ones from context; keep the original wording when unsure.
3. Relative dates such as "下周五" or "tomorrow" have already been annotated by the program with the actual date, e.g. "下周五（11月13日）"; use the date in the brackets. For any other relative date, keep the original wording and add the actual date in parentheses.
4. End every key point, decision and action item with its source timestamp, so it can be checked against the recording.
5. If a section has nothing, write "None" rather than dropping the section (except "Root Cause Analysis", see rule 7).
6. Leave out small talk and filler words; keep the language concise and formal.
7. Only include the "Root Cause Analysis" section when the meeting explicitly analyzed the causes of a problem: one fishbone code block per problem (at most two), 2-6 branches each, valid JSON. In refs, put the timestamps of the lines that support the cause; they must be [mm:ss] timestamps that appear in the transcript below (without the brackets). If no line supports it, leave refs as an empty array. If the meeting did not analyze causes, omit the whole section; do not write "None".
{glossary}
Transcript:
{transcript}

That is the whole transcript (there is nothing after this point). Now write the meeting minutes.
"""

    /**
     * 拼提示词时把转写压成「[mm:ss] 说话人：内容」，与桌面版一致。
     *
     * @param maxChars 提示词里最多放多少字符。远端模型的上下文比手机上的 0.6B 宽得多，
     *   所以给到 48k 字符（约两小时的会）；真遇到超长会议再截断，并明确告诉模型"后面没了"，
     *   否则它会当成全文都在、编出不存在的内容。
     */
    fun build(
        transcript: Transcript,
        maxChars: Int = 48_000,
        glossary: String = "",
        language: String = MinutesLanguage.ZH,
    ): String {
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
            body.take(maxChars) + if (isEnglish(language)) {
                "\n(The transcript is long; the rest has been omitted.)\n"
            } else {
                "\n（转写过长，后续内容已省略）\n"
            }
        }
        // 热词那一段由引擎产出，调用方按纪要语言取中/英那一份（`minutesGlossary(lang)`），
        // 所以这里直接放进来即可。没有热词时整行消失，不留一段空行。
        val template = if (isEnglish(language)) TEMPLATE_EN else TEMPLATE_ZH
        val terms = glossary.trim()
        val filled = if (terms.isEmpty()) {
            template.replace("{glossary}\n", "")
        } else {
            // 引擎给的就是一句完整的话（含"同音写法一律改成这些词"），直接放进来
            template.replace("{glossary}", terms)
        }
        return filled.replace("{transcript}", clipped)
    }

    /** 校验模型输出：该有的章节要在，鱼骨图 JSON 要能解析。 */
    fun validate(markdown: String, language: String = MinutesLanguage.ZH): List<String> {
        val problems = mutableListOf<String>()
        val en = isEnglish(language)
        if (!markdown.contains("# ")) problems += if (en) "missing title" else "缺少标题"
        val required = if (en) REQUIRED_SECTIONS_EN else REQUIRED_SECTIONS
        val missingLabel = if (en) "missing section: " else "缺少章节："
        required.filterNot { markdown.contains(it) }.forEach { problems += missingLabel + it }
        val fishboneSection = if (en) FISHBONE_SECTION_EN else FISHBONE_SECTION
        if (markdown.contains(fishboneSection)) {
            fishboneBodies(markdown).forEachIndexed { index, json ->
                val looksJson = json.startsWith("{") &&
                    json.contains("\"problem\"") &&
                    json.contains("\"branches\"")
                val parses = runCatching { org.json.JSONObject(json) }.isSuccess
                if (!looksJson || !parses) {
                    problems += if (en) "fishbone block ${index + 1} is not valid JSON" else "第 ${index + 1} 块鱼骨图不是合法 JSON"
                }
            }
            if (fishboneBodies(markdown).isEmpty()) {
                problems += if (en) "no fishbone code block in Root Cause Analysis" else "「原因分析」里没有 fishbone 代码块"
            }
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
    fun retryPrompt(
        original: String,
        problems: List<String>,
        language: String = MinutesLanguage.ZH,
    ): String = buildString {
        append(original)
        if (isEnglish(language)) {
            append("\n\nThe previous output did not meet the requirements (")
            append(problems.joinToString("; "))
            append("). Follow the structure above exactly and output only the Markdown body, with no explanation.\n")
        } else {
            append("\n\n上一次的输出不符合要求（")
            append(problems.joinToString("；"))
            append("）。请严格按上面的结构重新输出，只输出 Markdown 正文，不要解释。\n")
        }
    }

    private fun isEnglish(language: String): Boolean =
        MinutesLanguage.normalize(language) == MinutesLanguage.EN

    fun mmss(ms: Long): String = Transcript.mmss(ms)
}
