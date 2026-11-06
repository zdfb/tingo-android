package io.github.tancheng33.tingo.data

/**
 * 会议列表项的界面模型。
 *
 * 它是引擎 [io.github.tancheng33.tingo.engine.MeetingSummary] 的子集与投影：
 * 引擎负责「文件里有什么」，这里负责「列表要显示什么」。两者分开是为了让列表
 * 逻辑可以在 JVM 单测里跑，也让将来换数据源时界面不用改。
 */
data class MeetingCard(
    val id: String,
    val title: String,
    /** `YYYY-MM-DD` */
    val date: String,
    /** `HH:MM`，未知时为空串 */
    val time: String,
    /** 转写时长，未知时为空串 */
    val duration: String,
    val oneLiner: String,
    val tags: List<String>,
    val archived: Boolean,
    val hasAudio: Boolean,
    val hasMinutes: Boolean,
    /**
     * 这个会议目录没能读出来（例如 `transcript.json` 损坏）。
     * 它仍然出现在列表里，但界面要标记出来，而不是悄悄消失。
     */
    val unreadable: Boolean = false,
)
