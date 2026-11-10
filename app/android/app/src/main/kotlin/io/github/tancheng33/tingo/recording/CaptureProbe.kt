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
    val heardSomething: Boolean = failure == null && (peak ?: 0f) >= VOICE_LEVEL

    companion object {
        /**
         * "有声音"的门限：与桌面 `/api/record/test` 用的是同一个数（0.01）。
         *
         * **不要**复用 [SilenceWatcher.VOICE_LEVEL]（0.05）：那边判的是"这段会议里有人在说话"，
         * 门槛高得多才不会把空调声当人声；试录只回答"麦克风有没有真的收到东西"，
         * 门槛高了会把正常的安静房间判成"没声音"，与桌面结论相反。
         */
        const val VOICE_LEVEL = 0.01f
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
    val step = tickMs.coerceAtLeast(1L)
    var peak = 0f
    try {
        capture.start(scratch)
    } catch (e: Exception) {
        scratch.delete()
        return CaptureProbeResult(span, peak = null, failure = e)
    }
    var samplingFailure: Exception? = null
    try {
        val ticks = (span * 1000L / step).toInt()
        repeat(ticks) {
            peak = maxOf(peak, capture.level())
            delay(step)
        }
    } catch (e: Exception) {
        // 取样中途炸了：如实回报，别继续当成"在跑"
        samplingFailure = e
    }
    // 先停再读失败原因：停止本身也可能失败（wav 头写不进去），
    // 那时候用户看到的是"没声音"而不是真实原因
    val stopFailure = runCatching { capture.stop() }.exceptionOrNull()
    // 探针文件留着只会让人在缓存目录里看到一堆 3 秒的空录音
    scratch.delete()
    val failure = samplingFailure ?: capture.lastFailure() ?: stopFailure
    return CaptureProbeResult(span, peak = peak, failure = failure)
}

/** 试录时长：与桌面按钮上的"试录 3 秒"一致。 */
const val DEFAULT_SECONDS = 3

/** 上限与桌面 `/api/record/test` 一致（1–10 秒）。 */
const val MAX_SECONDS = 10
