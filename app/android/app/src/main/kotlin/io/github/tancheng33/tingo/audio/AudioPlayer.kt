package io.github.tancheng33.tingo.audio

import java.io.File

/**
 * 播放一场会议的录音。
 *
 * 抽成接口是为了让"点击时间戳跳到该句"这条逻辑能被单测覆盖：真机上播放器要靠人耳确认，
 * 但"该不该 seek、seek 到哪、播放状态怎么变"完全可以在 JVM 里断言。
 */
interface AudioPlayer {
    /** 打开文件并准备播放；[onReady] 回调总时长（毫秒）。 */
    fun prepare(file: File, onReady: (durationMs: Long) -> Unit)

    fun play()

    fun pause()

    /** 跳到 [positionMs]；越界由实现自己夹住。 */
    fun seekTo(positionMs: Long)

    /** 当前播放位置（毫秒）。 */
    val positionMs: Long

    val isPlaying: Boolean

    /** 释放底层资源；重复调用无副作用。 */
    fun release()
}

/** 播放器的对外状态：界面据此画进度条与按钮。 */
data class PlaybackState(
    val available: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
) {
    /** 0..1，时长未知时为 0。 */
    val progress: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}
