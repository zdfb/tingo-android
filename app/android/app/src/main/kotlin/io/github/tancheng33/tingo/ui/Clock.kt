package io.github.tancheng33.tingo.ui

/** 毫秒 → `mm:ss`（不足一小时）或 `hh:mm:ss`。与桌面端转写时间戳的写法一致。 */
fun clockOf(ms: ULong): String {
    val totalSeconds = ms.toLong() / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
