package io.github.tancheng33.tingo.minutes

/**
 * 纪要语言：设置里是 `auto | zh | en`（与桌面 `[minutes] language` 同一套取值）。
 *
 * `auto` 的含义是"跟会议说的话一致"——只看转写正文，不看说话人名，
 * 否则英文会议的说话人叫"说话人1"，会被判成中文会议。
 */
object MinutesLanguage {
    const val AUTO = "auto"
    const val ZH = "zh"
    const val EN = "en"

    /** 设置值归一化：认 `zh`/`en`/`auto` 与 `zh-CN` 这类写法，认不出按 auto。 */
    fun normalize(setting: String): String = when {
        setting.trim().startsWith("en", ignoreCase = true) -> EN
        setting.trim().startsWith("zh", ignoreCase = true) -> ZH
        else -> AUTO
    }

    /**
     * 设置 + 转写正文 → 这一场实际用哪种语言写纪要。
     *
     * 只认精确的 `zh` / `en`，其余（含 `auto`、空、`zh-CN` 这种带地区的）都按"跟会议走"——
     * 与桌面 `i18n::resolve` 的匹配规则一致，两边读同一份配置不会给出不同结论。
     */
    fun resolve(setting: String, sentences: List<String>): String =
        when (setting.trim().lowercase()) {
            ZH -> ZH
            EN -> EN
            else -> detect(sentences)
        }

    /**
     * 正文语言：汉字占「汉字 + 拉丁字母」≥30% 算中文（中文会议里夹英文术语很常见）。
     *
     * 字符分类与门限逐条照抄桌面 `i18n::is_cjk` / `is_latin` / `detect`：同一场会议在两端
     * 必须得出同一个结论，否则"跟会议语言"在手机上写英文、在桌面上写中文。
     */
    fun detect(sentences: List<String>): String {
        var cjk = 0
        var latin = 0
        sentences.forEach { text ->
            text.forEach { c ->
                when {
                    isCjk(c) -> cjk++
                    isLatin(c) -> latin++
                }
            }
        }
        val total = cjk + latin
        return if (total == 0 || cjk.toDouble() >= 0.3 * total) ZH else EN
    }

    /** 与桌面一致：基本区、扩展 A、兼容汉字、扩展 B（不含假名/谚文）。 */
    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return code in 0x4E00..0x9FFF ||
            code in 0x3400..0x4DBF ||
            code in 0xF900..0xFAFF ||
            code in 0x20000..0x2FFFF
    }

    /** 与桌面一致：ASCII 字母，或拉丁补充/扩展区里的字母（é、ø 这类算拉丁）。 */
    private fun isLatin(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || (c.code in 0xC0..0x24F && c.isLetter())
}
