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
        setting.startsWith("en", ignoreCase = true) -> EN
        setting.startsWith("zh", ignoreCase = true) -> ZH
        else -> AUTO
    }

    /** 设置 + 转写正文 → 这一场实际用哪种语言写纪要。 */
    fun resolve(setting: String, sentences: List<String>): String =
        when (val s = normalize(setting)) {
            AUTO -> detect(sentences)
            else -> s
        }

    /**
     * 正文语言：中日韩字符占比 ≥30% 算中文，否则英文（与桌面 `i18n::detect` 同一门限）。
     *
     * 没有任何字母汉字时按中文——中文界面的用户拿到的空转写更可能是中文会议。
     */
    fun detect(sentences: List<String>): String {
        var cjk = 0
        var latin = 0
        sentences.forEach { text ->
            text.forEach { c ->
                when {
                    isCjk(c) -> cjk++
                    c.isLetter() && c.code < 0x250 -> latin++
                }
            }
        }
        val total = cjk + latin
        return if (total == 0 || cjk.toDouble() >= 0.3 * total) ZH else EN
    }

    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return code in 0x3400..0x4DBF || // 扩展 A
            code in 0x4E00..0x9FFF || // 基本区
            code in 0xF900..0xFAFF || // 兼容汉字
            code in 0x3040..0x30FF || // 假名（日文会议也走中文模板更合适）
            code in 0xAC00..0xD7AF // 谚文
    }
}
