package io.github.tancheng33.tingo.ui.detail

import io.github.tancheng33.tingo.engine.Fishbone
import io.github.tancheng33.tingo.engine.Transcript

/**
 * 鱼骨图的"每条分支到底有没有原话支撑"。
 *
 * 规则逐条照抄桌面 `fishbone.ts`：时间戳落在某段发言的范围内就算引用了那段话，
 * 一条都对不上标「无原文依据」，对上了但正文总字数不足 [WEAK_CHARS] 标「依据很少」。
 * 这是产品卖点（结论有出处）在手机上的落点——只画图不核对，等于把"编出来的原因"
 * 和"有原话的原因"画成一样。
 */
object FishboneGrounding {

    /** 引用的正文不到这么多字，算"依据很少"（与桌面同一个门限）。 */
    const val WEAK_CHARS = 20

    /** 一条引用对应的原话。 */
    data class Quote(
        /** 这句话在录音里的起点（点它从这里开始播） */
        val ms: Long,
        val speaker: String,
        val text: String,
        /** 纪要里写的时间戳原文（00:05 之类）：界面显示它，而不是被吸附到的句子起点 */
        val ref: String = "",
    )

    enum class Level { OK, WEAK, NONE }

    private val TIMESTAMP_PATTERN = Regex("""\d{1,2}:\d{2}(:\d{2})?""")

    data class Grounding(val quotes: List<Quote>, val chars: Int, val level: Level)

    /** 整个图的核对结果：分支数、引用段数、无依据的分支数。 */
    data class Summary(val branches: Int, val quotes: Int, val withoutEvidence: Int)

    /**
     * 把 `[mm:ss]` 解析成毫秒并找到对应的那句发言。
     *
     * 规则与桌面 `turnResolver` 一致："时间戳落在某段发言**起点之后 1 秒内**就算这段"，
     * 也就是取"最后一个起点 ≤ ms+999 的发言"。所以落在一段发言中间的时间戳会算到这段上，
     * 而落在两段之间的会算到**前一段**——这是桌面的真实行为，不是"区间包含"。
     *
     * @return 找不到（格式不对、超出时长、早于第一段发言之前）时返回 null
     */
    fun resolve(ref: String, transcript: Transcript): Quote? {
        val ms = parseTimestamp(ref) ?: return null
        // 时长未知（0，例如从别的设备导入的 transcript.json）时不做上限判断——
        // 桌面在同样情况下传的是 Infinity：不知道就别拿"超出长度"当借口把依据判没
        val duration = transcript.durationMs.toLong()
        if (duration > 0 && ms > duration + 1_000) return null
        val turns = transcript.turns
        // 时间戳落在"从这段话开始往后 1 秒内"都算这段：模型常给整秒，发言起点带毫秒
        var hit: Int = -1
        turns.forEachIndexed { index, turn ->
            if (turn.startMs.toLong() <= ms + 999) hit = index
        }
        val turn = turns.getOrNull(hit) ?: return null
        return Quote(
            ms = turn.startMs.toLong(),
            speaker = turn.speakerName,
            text = turn.text,
            ref = cleanRef(ref),
        )
    }

    fun ground(refs: List<String>, transcript: Transcript?): Grounding {
        val quotes = split(refs, transcript).quotes
        // 按码点数：桌面用 `[...text].length`，代理对（表情、生僻字）两边要数成一个字
        val chars = quotes.sumOf { quote ->
            quote.text.codePointCount(0, quote.text.length)
        }
        val level = when {
            quotes.isEmpty() -> Level.NONE
            chars < WEAK_CHARS -> Level.WEAK
            else -> Level.OK
        }
        return Grounding(quotes, chars, level)
    }

    /**
     * 折叠视图要的东西：核对上的引用（去重、按时间排）与对不上的原文。
     *
     * 两者都要给：只画核对上的，等于把"这条分支引用了什么"藏掉一半；
     * 只画原文，那堆时间戳又没法点。
     */
    data class RefChips(val quotes: List<Quote>, val unresolved: List<String>)

    fun split(refs: List<String>, transcript: Transcript?): RefChips {
        // 一次遍历：对得上的按毫秒去重（同一句话只说一次），对不上的按原文去重
        val bySecond = LinkedHashMap<Long, Quote>()
        val unresolved = LinkedHashSet<String>()
        refs.forEach { ref ->
            val quote = transcript?.let { resolve(ref, it) }
            if (quote != null) bySecond.putIfAbsent(quote.ms, quote) else unresolved += ref
        }
        return RefChips(quotes = bySecond.values.sortedBy { it.ms }, unresolved = unresolved.toList())
    }

    fun summarize(fishbone: Fishbone, transcript: Transcript?): Summary {
        val branches = fishbone.branches.filter { it.title.isNotBlank() }
        val grounded = branches.map { ground(it.refs, transcript) }
        return Summary(
            branches = branches.size,
            quotes = grounded.sumOf { it.quotes.size },
            withoutEvidence = grounded.count { it.level == Level.NONE },
        )
    }

    /** 去掉方括号与空白：`[00:12]` → `00:12`。 */
    fun cleanRef(ref: String): String = ref.trim().removePrefix("[").removeSuffix("]")

    /** `mm:ss` / `h:mm:ss` → 毫秒；认不出返回 null（与桌面 `tsToMs` 同一套写法）。 */
    fun parseTimestamp(ref: String): Long? {
        val clean = cleanRef(ref)
        // 桌面只认 `^\d{1,2}:\d{2}(:\d{2})?$`：别的写法（"稍后""12点"）一律不算依据
        if (!TIMESTAMP_PATTERN.matches(clean)) return null
        val parts = clean.split(':')
        if (parts.size !in 2..3) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        if (numbers.any { it < 0 }) return null
        val seconds = when (numbers.size) {
            2 -> numbers[0] * 60L + numbers[1]
            else -> numbers[0] * 3_600L + numbers[1] * 60L + numbers[2]
        }
        return seconds * 1_000L
    }
}
