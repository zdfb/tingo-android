package io.github.tancheng33.tingo.recording

/**
 * 录音状态机。
 *
 * 只做时间与状态，不碰音频设备——这样"暂停 10 秒再继续，总时长不含这 10 秒"这类规则
 * 可以直接在 JVM 单测里用假时钟验证，不需要真麦克风。
 */
class RecordingSession {

    sealed interface State {
        /** 还没开始，或已经结束（`audioBytes` 为已写字节数） */
        data object Idle : State

        data class Recording(val startedAtMs: Long) : State

        /** @param accumulatedMs 暂停前已经录到的毫秒数 */
        data class Paused(val accumulatedMs: Long) : State

        data class Finished(val durationMs: Long) : State

        data class Failed(val reason: String) : State
    }

    var state: State = State.Idle
        private set

    /** 已经累计的录音时长（不含暂停），按 `nowMs` 计算。 */
    fun elapsedMs(nowMs: Long): Long = when (val s = state) {
        is State.Recording -> accumulatedMs + (nowMs - s.startedAtMs)
        is State.Paused -> s.accumulatedMs
        is State.Finished -> s.durationMs
        else -> 0
    }

    private var accumulatedMs: Long = 0

    fun start(nowMs: Long) {
        state = State.Recording(startedAtMs = nowMs)
        accumulatedMs = 0
    }

    fun pause(nowMs: Long) {
        val s = state as? State.Recording ?: error("只有在录音中才能暂停")
        accumulatedMs += nowMs - s.startedAtMs
        state = State.Paused(accumulatedMs)
    }

    fun resume(nowMs: Long) {
        val s = state as? State.Paused ?: error("只有在暂停中才能继续")
        accumulatedMs = s.accumulatedMs
        state = State.Recording(startedAtMs = nowMs)
    }

    /** 结束；`nowMs` 用于计算最后一段时长。 */
    fun finish(nowMs: Long): Long {
        val total = elapsedMs(nowMs)
        state = State.Finished(total)
        return total
    }

    fun fail(reason: String) {
        state = State.Failed(reason)
    }
}
