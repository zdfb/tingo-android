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

    private const val TEMPLATE_ZH = """下面是一场会议的语音转写，格式为「[时间戳] 说话人：内容」。会议日期：{meeting_date}。

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

```terms
{"terms": [{"term": "历史工单", "variants": ["历史中单"]}]}
```

要求：
1. 只依据转写内容，不编造。负责人或截止时间没有明确说出的，写"未明确"。
2. 转写有同音错字，结合上下文改正明显的错误；拿不准的保留原文。
3. 转写里的"下周五""明天"这类相对时间，按会议日期推算成具体日期，并在括号里写出来，
   如"下周五（11月13日）"；拿不准就保留原话，不要编。
4. 每条要点、决议、待办都附上出处时间戳，便于回听核对。
5. 某一节没有内容时写"无"，不要删掉这一节（「原因分析」除外，见第 7 条）。
6. 去掉寒暄和口头禅，语言简洁、书面化。
7. 只有会议里明确分析了某个问题的原因时，才写「原因分析」这一节：每个问题一个 fishbone
   代码块（最多两个），每块 2～6 个分支，内容是合法 JSON。refs 填支撑这个原因的原话时间戳，
   必须是上面转写里出现过的 [mm:ss]（不带方括号）；找不到原话支撑就留空数组，不要编。
   没有分析原因的会议，整节省略，不要写"无"。
8. 全文最后（鱼骨图之后）必须原样输出 terms 代码块：列出会上出现的人名、产品、项目、
   专有名词的正确写法（优先用术语表、热词里的写法），variants 写转写里出现过的听错的写法
   （没有就写空数组）；最多 30 个；一个都没有时写 {"terms": []}。纪要正文里一律用正确写法。
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

```terms
{"terms": [{"term": "Kafka", "variants": ["Kafca"]}]}
```

Rules:
1. Use only what is in the transcript; do not make anything up. If an owner or a due date was not stated explicitly, write "Not specified".
2. The transcript has recognition errors (homophones, misheard words). Fix obvious ones from context; keep the original wording when unsure.
3. Relative dates such as "下周五" or "tomorrow" have already been annotated by the program with the actual date, e.g. "下周五（11月13日）"; use the date in the brackets. For any other relative date, keep the original wording and add the actual date in parentheses.
4. End every key point, decision and action item with its source timestamp, so it can be checked against the recording.
5. If a section has nothing, write "None" rather than dropping the section (except "Root Cause Analysis", see rule 7).
6. Leave out small talk and filler words; keep the language concise and formal.
7. Only include the "Root Cause Analysis" section when the meeting explicitly analyzed the causes of a problem: one fishbone code block per problem (at most two), 2-6 branches each, valid JSON. In refs, put the timestamps of the lines that support the cause; they must be [mm:ss] timestamps that appear in the transcript below (without the brackets). If no line supports it, leave refs as an empty array. If the meeting did not analyze causes, omit the whole section; do not write "None".
8. At the very end (after the fishbone), always output the terms code block exactly as shown above: list the correct spelling of the names of people, products, projects and other proper nouns that came up in the meeting (prefer the spelling in the glossary / hotwords), and in variants put the misheard spellings that appear in the transcript (an empty array if none); at most 30 entries; if there are none, write {"terms": []}. Always use the correct spelling in the body of the minutes.{glossary}{translate}

Transcript:
{transcript}

That is the whole transcript (there is nothing after this point). Now write the meeting minutes.
"""


    /** 中文分段提炼提示词（桌面 `CHUNK_PROMPT`）。 */
    private const val CHUNK_PROMPT_ZH = """下面是一场长会议中的一段转写（第 {index}/{total} 段），格式为「[时间戳] 说话人：内容」。会议日期：{meeting_date}。

请提炼这一段的：讨论要点、已拍板的决议、待办（事项/负责人/截止时间）、待定问题与风险。
每条都附出处时间戳 [mm:ss]，只依据原文，不编造，用简洁的条目输出。{glossary}{translate}

转写：
{transcript}
"""

    /** 英文分段提炼提示词（桌面 `CHUNK_PROMPT_EN`）。 */
    private const val CHUNK_PROMPT_EN = """Below is one part (part {index} of {total}) of the transcript of a long meeting, one line per turn in the form "[timestamp] Speaker: content". Meeting date: {meeting_date}

Extract from this part: key discussion points, decisions made, action items (item / owner / due date), and open questions & risks.
End every entry with its source timestamp [mm:ss]. Use only what is in the transcript, do not make anything up, and output concise bullet points.{glossary}{translate}

Transcript:
{transcript}
"""

    /** 中文合并提示词（桌面 `REDUCE_PROMPT`）。 */
    private const val REDUCE_PROMPT_ZH = """下面是一场长会议按时间顺序分段提炼的笔记。请把它们合并成一份完整的会议纪要，去重、按议题归并，保留出处时间戳。

{instructions}

分段笔记：
{notes}
"""

    /** 英文合并提示词（桌面 `REDUCE_PROMPT_EN`）。 */
    private const val REDUCE_PROMPT_EN = """Below are notes taken part by part, in chronological order, from a long meeting. Merge them into one complete set of meeting minutes: remove duplicates, group points by topic, and keep the source timestamps.

{instructions}

Notes by part:
{notes}
"""

    /**
     * 拼提示词时把转写压成「[mm:ss] 说话人：内容」，与桌面版一致。
     *
     * @param maxChars 提示词里最多放多少字符：与桌面远端模型同为 40000。
     *   调用方（`MinutesService`）在这之前就会把超长的会议分段提炼，所以这里的截断只是兜底——
     *   真要截断也要明确告诉模型"后面没了"，否则它会当成全文都在、编出不存在的内容。
     */
    fun build(
        transcript: Transcript,
        maxChars: Int = MinutesService.DEFAULT_MAX_CHARS,
        glossary: String = "",
        language: String,
        meetingDate: String? = null,
        /** 转写说的话是哪一种语言；与 `language` 不同时要明说"翻译" */
        spokenLanguage: String? = null,
    ): String {
        val body = buildString {
            val english = isEnglish(language)
            transcript.sentences.forEach { sentence ->
                append('[').append(Transcript.mmss(sentence.startMs)).append("] ")
                // 英文纪要里没改过名的说话人叫 Speaker N（与桌面 `transcript.localized(lang)` 一致）
                val label = transcript.speakerLabel(sentence.speaker)
                append(if (english) englishSpeakerLabel(label) else label).append(if (english) ": " else "：")
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
        return filledTemplate(language, glossary, meetingDate, spokenLanguage)
            .replace("{transcript}", clipped)
    }

    /** 填好语言相关占位符的模板（不含转写正文）。合并提示词只取它前半段的格式要求。 */
    private fun filledTemplate(
        language: String,
        glossary: String,
        meetingDate: String?,
        spokenLanguage: String?,
    ): String {
        val template = if (isEnglish(language)) TEMPLATE_EN else TEMPLATE_ZH
        val terms = glossary.trim()
        val filled = if (terms.isEmpty()) {
            // 中文模板里占位符独占一行；紧贴 {translate} 的写法在英文模板里，两种都要清掉
            template.replace("{glossary}\n", "").replace("{glossary}", "")
        } else {
            // 引擎给的就是一句完整的话（含"同音写法一律改成这些词"），直接放进来
            template.replace("{glossary}", terms)
        }
        return filled
            .replace("{meeting_date}", meetingDate.orEmpty())
            .replace("{translate}", translateNote(language, spokenLanguage))
    }

    /** 转写正文行：`[mm:ss] 说话人：内容`，一行一句。分段提炼与单次生成共用它。 */
    fun transcriptLines(transcript: Transcript, language: String): List<String> {
        val english = isEnglish(language)
        return transcript.sentences.map { sentence ->
            buildString {
                append('[').append(Transcript.mmss(sentence.startMs)).append("] ")
                // 英文纪要里没改过名的说话人叫 Speaker N（与桌面 `transcript.localized(lang)` 一致）
                val label = transcript.speakerLabel(sentence.speaker)
                append(if (english) englishSpeakerLabel(label) else label)
                append(if (english) ": " else "：")
                append(sentence.text.trim())
            }
        }
    }

    /** 按行切段：每段不超过 `maxChars` 个字符，不切断一行。 */
    fun splitChunks(lines: List<String>, maxChars: Int): List<String> {
        val chunks = mutableListOf<String>()
        val buffer = mutableListOf<String>()
        var size = 0
        lines.forEach { line ->
            val n = line.length
            if (buffer.isNotEmpty() && size + n > maxChars) {
                chunks += buffer.joinToString("\n")
                buffer.clear()
                size = 0
            }
            buffer += line
            size += n + 1
        }
        if (buffer.isNotEmpty()) chunks += buffer.joinToString("\n")
        return chunks
    }

    /**
     * 长会议的分段提炼提示词（对应桌面 `CHUNK_PROMPT`）。
     *
     * 长转写整段喂给模型，待办会被大段无关内容稀释；桌面因此先分段摘条目再合并。
     */
    fun chunkPrompt(
        chunk: String,
        index: Int,
        total: Int,
        language: String,
        meetingDate: String? = null,
        glossary: String = "",
        spokenLanguage: String? = null,
    ): String {
        val template = if (isEnglish(language)) CHUNK_PROMPT_EN else CHUNK_PROMPT_ZH
        return template
            .replace("{index}", index.toString())
            .replace("{total}", total.toString())
            .replace("{meeting_date}", meetingDate.orEmpty())
            .replace("{glossary}", glossary.trim())
            .replace("{translate}", translateNote(language, spokenLanguage))
            .replace("{transcript}", chunk)
    }

    /** 把分段笔记合并成最终纪要（对应桌面 `REDUCE_PROMPT`）。 */
    fun reducePrompt(
        notes: List<String>,
        language: String,
        meetingDate: String? = null,
        glossary: String = "",
        spokenLanguage: String? = null,
    ): String {
        val english = isEnglish(language)
        val template = if (english) REDUCE_PROMPT_EN else REDUCE_PROMPT_ZH
        // 合并时只带"格式要求"那一段，不带单段提示词里的"以上是全部转写"尾巴
        val marker = if (english) "\nTranscript:" else "转写："
        val instructions = filledTemplate(
            language = language,
            glossary = glossary,
            meetingDate = meetingDate,
            spokenLanguage = spokenLanguage,
        ).substringBefore(marker)
        return template
            .replace("{instructions}", instructions)
            .replace("{notes}", notes.joinToString("\n\n"))
    }

    /** 校验模型输出：该有的章节要在，鱼骨图 JSON 要能解析。 */
    fun validate(markdown: String, language: String): List<String> {
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
        language: String,
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

    /**
     * 转写和纪要不是同一种语言时（用户手动选了纪要语言），明确要求翻译。
     *
     * 与桌面 `translate_note` 同一句话：不写这一句，模型常常把中文正文原样抄进英文纪要。
     */
    private fun translateNote(minutes: String, spoken: String?): String {
        if (spoken == null) return ""
        val minutesEn = isEnglish(minutes)
        val spokenEn = isEnglish(spoken)
        return when {
            minutesEn && !spokenEn ->
                "\n\nThe transcript is in Chinese: write the minutes in English, translating as needed (keep names of people, products and systems as they are)."
            !minutesEn && spokenEn ->
                "\n\n注意：转写是英文的，纪要一律用中文撰写，需要时翻译（人名、产品名、系统名等专有名词保留原文）。"
            else -> ""
        }
    }

    /**
     * 把默认的"说话人3"换成"Speaker 3"。
     *
     * 只换引擎生成的默认标签：用户自己起的名字原样保留（人名不该被翻译）。
     */
    private fun englishSpeakerLabel(label: String): String {
        val match = Regex("^说话人(\\d+)$").find(label.trim()) ?: return label
        return "Speaker " + match.groupValues[1]
    }

    private fun isEnglish(language: String): Boolean =
        MinutesLanguage.normalize(language) == MinutesLanguage.EN

    fun mmss(ms: Long): String = Transcript.mmss(ms)
}
