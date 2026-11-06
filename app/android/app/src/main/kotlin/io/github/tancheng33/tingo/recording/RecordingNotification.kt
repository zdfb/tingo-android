package io.github.tancheng33.tingo.recording

import io.github.tancheng33.tingo.ui.record.RecordUiState

/**
 * 录音期间要不要挂前台服务。
 *
 * 只有"正在录"和"暂停但没结束"需要：其余状态要么还没开始，要么文件已经写完了。
 * 单独抽成函数是因为这是纯判断，能在 JVM 单测里钉住——真机上漏了这一步的表现是
 * 锁屏后录音被系统掐掉，事后很难复现。
 */
fun requiresForegroundService(state: RecordUiState): Boolean = when (state) {
    is RecordUiState.Recording, is RecordUiState.Paused -> true
    RecordUiState.Idle, is RecordUiState.Finished, is RecordUiState.Failed -> false
}

/**
 * 通知栏文案的数据源。
 *
 * 前台服务必须有一条常驻通知；这条通知也是用户"还在录吗"的唯一确认，
 * 所以暂停时要明确写"已暂停"，而不是继续显示计时。
 */
object RecordingNotification {
    const val CHANNEL_ID = "tingo-recording"
    const val NOTIFICATION_ID = 1

    /** @param elapsed 已格式化的时长（mm:ss 或 h:mm:ss） */
    fun title(recording: Boolean, elapsed: String): String =
        if (recording) "正在录音 · $elapsed" else "已暂停 · $elapsed"
}
