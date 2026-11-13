package io.github.tancheng33.tingo.wear

import io.github.zdfb.tingo.wear.protocol.WearAudioFrame
import io.github.zdfb.tingo.wear.protocol.WearSequenceResult
import io.github.zdfb.tingo.wear.protocol.WearSequenceTracker

/** 手机端接收手表音频的纯状态机；Data Layer 适配层只负责把字节交给它。 */
class WearStreamReceiver(
    private val onAudio: (WearAudioFrame) -> Unit,
    private val onGap: (expected: Long, received: Long) -> Unit = { _, _ -> },
) {
    private var sessionId: String? = null
    private var tracker = WearSequenceTracker()
    private var receiving = false

    val isReceiving: Boolean get() = receiving
    val activeSessionId: String? get() = sessionId

    fun begin(session: String) {
        require(session.isNotBlank()) { "sessionId 不能为空" }
        sessionId = session
        tracker = WearSequenceTracker()
        receiving = true
    }

    fun accept(frame: WearAudioFrame): WearReceiveResult {
        if (!receiving || frame.sessionId != sessionId) return WearReceiveResult.IgnoredSession
        return when (val result = tracker.accept(frame.sequence)) {
            WearSequenceResult.Accepted -> {
                onAudio(frame)
                WearReceiveResult.Accepted
            }
            WearSequenceResult.Duplicate -> WearReceiveResult.Duplicate
            is WearSequenceResult.Gap -> {
                onGap(result.expected, result.received)
                WearReceiveResult.Gap(result.expected, result.received)
            }
        }
    }

    fun finish(session: String): Boolean {
        if (!receiving || session != sessionId) return false
        receiving = false
        return true
    }
}

sealed interface WearReceiveResult {
    data object Accepted : WearReceiveResult
    data object Duplicate : WearReceiveResult
    data object IgnoredSession : WearReceiveResult
    data class Gap(val expected: Long, val received: Long) : WearReceiveResult
}
