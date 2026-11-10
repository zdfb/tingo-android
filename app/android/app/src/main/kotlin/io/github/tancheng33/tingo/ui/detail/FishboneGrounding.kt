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
    data class Quote(val ms: Long, val speaker: String, val text: String)

    enum class Level { OK, WEAK, NONE }

    data class Grounding(val quotes: List<Quote>, val chars: Int, val level: Level)

    /** 整个图的核对结果：分支数、引用段数、无依据的分支数。 */
    data class Summary(val branches: Int, val quotes: Int, val withoutEvidence: Int)

    /**
     * 把 `[mm:ss]` 解析成毫秒并找到对应的那句发言。
     *
     * @return 找不到（格式不对、超出时长、时间点在第一句之前）时返回 null
     */
    fun resolve(ref: String, transcript: Transcript): Quote? {
        val ms = parseTimestamp(ref) ?: return null
        val duration = transcript.durationMs.toLong()
        if (duration > 0 && ms > duration + 1_000) return null
        val turns = transcript.turns
        // 时间戳落在"从这段话开始往后 1 秒内"都算这段：模型常给整秒，发言起点带毫秒
        var hit: Int = -1
        turns.forEachIndexed { index, turn ->
            if (turn.startMs.toLong() <= ms + 999) hit = index
        }
        val turn = turns.getOrNull(hit) ?: return null
        return Quote(ms = turn.startMs.toLong(), speaker = turn.speakerName, text = turn.text)
    }

    fun ground(refs: List<String>, transcript: Transcript?): Grounding {
        if (transcript == null) return Grounding(emptyList(), 0, Level.NONE)
        val seen = HashSet<Long>()
        val quotes = refs
            .mapNotNull { resolve(it, transcript) }
            .filter { seen.add(it.ms) }
            .sortedBy { it.ms }
        val chars = quotes.sumOf { it.text.length }
        val level = when {
            quotes.isEmpty() -> Level.NONE
            chars < WEAK_CHARS -> Level.WEAK
            else -> Level.OK
        }
        return Grounding(quotes, chars, level)
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

    /** `mm:ss` / `h:mm:ss` → 毫秒；认不出返回 null（与桌面 `tsToMs` 同一套写法）。 */
    fun parseTimestamp(ref: String): Long? {
        val clean = ref.trim().removePrefix("[").removeSuffix("]")
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
