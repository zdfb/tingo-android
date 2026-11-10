package io.github.tancheng33.tingo.recording

import java.io.File
import kotlinx.coroutines.delay

/**
 * 试录的结果：桌面端 `/api/record/test` 回答的是同一件事——"有没有权限、有没有声音"。
 *
 * @param peak 这 [seconds] 秒里最高的电平（0..1）；null 表示没采样到（样例太少）
 * @param failure 录音打不开时的原因，给用户看的那句话由界面决定
 */
data class CaptureProbeResult(
    val seconds: Int,
    val peak: Float?,
    val failure: Throwable? = null,
) {
    /** 没出错、而且电平高于底噪：说明麦克风确实在工作。 */
    val heardSomething: Boolean = failure == null && (peak ?: 0f) > VOICE_LEVEL

    companion object {
        /**
         * "有声音"的门限，与 [SilenceWatcher] 判定"有人说话"同一条刻度（峰值）。
         *
         * 两个地方用同一个门槛才不会出现"试录说有声音、真录却被判静音自动结束"。
         */
        const val VOICE_LEVEL = SilenceWatcher.VOICE_LEVEL
    }
}

/**
 * 试录几秒，回答"麦克风有没有权限、有没有声音"。
 *
 * 桌面端在引导第三步会真的录一段（`/api/record/test`，1–10 秒）；Android 之前只把
 * "已授权 / 未授权"写成一行动态文字，用户看不出麦克风到底有没有真的收到声音
 * （授权了但设备被占用、或系统把输入静音了，都是常见故障）。
 *
 * 试录的音轨只是探针，用完立刻删掉：不写进会议库，也不占用户空间。
 */
suspend fun probeCapture(
    capture: AudioCapture,
    scratch: File,
    seconds: Int = DEFAULT_SECONDS,
    tickMs: Long = 50L,
): CaptureProbeResult {
    val span = seconds.coerceIn(1, MAX_SECONDS)
    var peak = 0f
    try {
        capture.start(scratch)
    } catch (e: Exception) {
        scratch.delete()
        return CaptureProbeResult(span, peak = null, failure = e)
    }
    try {
        val ticks = (span * 1000L / tickMs).toInt()
        repeat(ticks) {
            peak = maxOf(peak, capture.level())
            delay(tickMs)
        }
        val failure = capture.lastFailure()
        return CaptureProbeResult(span, peak = peak, failure = failure)
    } finally {
        // 收尾一定要走到：探针文件留着只会让人在缓存目录里看到一堆 3 秒的空录音
        runCatching { capture.stop() }
        scratch.delete()
    }
}

/** 试录时长：与桌面按钮上的"试录 3 秒"一致。 */
const val DEFAULT_SECONDS = 3

/** 上限与桌面 `/api/record/test` 一致（1–10 秒）。 */
const val MAX_SECONDS = 10
