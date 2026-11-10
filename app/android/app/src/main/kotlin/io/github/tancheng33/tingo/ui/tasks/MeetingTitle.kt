package io.github.tancheng33.tingo.ui.tasks

/**
 * 从会议目录名取出给人看的标题。
 *
 * 目录名形如 `2026-10-27_0930_项目A`（引擎的 `FOLDER` 规则），首页那块"进行中"要显示的是
 * 标题而不是一串带时间戳的目录名。**不能**用"取最后一个下划线之后"这种做法：
 * 标题本身可能带下划线（英文示例会议就是 `..._Sample__Signup_conversion_review`），
 * 那样只会剩下 `review`。
 */
fun meetingTitleOf(dirName: String): String {
    val title = dirName.replace(FOLDER_PREFIX, "")
    return title.ifBlank { dirName }
}

/** 与引擎 `new_folder` 一致的时间前缀：`yyyy-MM-dd_HHmm_` */
private val FOLDER_PREFIX = Regex("^\\d{4}-\\d{2}-\\d{2}_\\d{4}_")
