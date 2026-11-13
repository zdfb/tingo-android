package io.github.tancheng33.tingo.wear

import io.github.zdfb.tingo.wear.protocol.WearAudioFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 手机端把 Wear Data Layer 与现有实时识别管线隔开的桥。 */
class WearAudioBridge {
    private val _state = MutableStateFlow<WearBridgeState>(WearBridgeState.Idle)
    val state: StateFlow<WearBridgeState> = _state.asStateFlow()
    @Volatile private var audioListener: ((WearAudioFrame) -> Unit)? = null
    private var receiver = WearStreamReceiver(onAudio = { frame -> audioListener?.invoke(frame) })

    fun attach(listener: ((WearAudioFrame) -> Unit)?) {
        audioListener = listener
    }

    fun begin(sessionId: String) {
        receiver = WearStreamReceiver(onAudio = { frame -> audioListener?.invoke(frame) })
        receiver.begin(sessionId)
        _state.value = WearBridgeState.Receiving(sessionId)
    }

    fun accept(frame: WearAudioFrame): WearReceiveResult {
        val result = receiver.accept(frame)
        if (result is WearReceiveResult.Gap) {
            _state.value = WearBridgeState.MissingFrames(result.expected, result.received)
        }
        return result
    }

    fun finish(sessionId: String) {
        if (receiver.finish(sessionId)) _state.value = WearBridgeState.Finished(sessionId)
    }

    fun reset() {
        receiver = WearStreamReceiver(onAudio = { frame -> audioListener?.invoke(frame) })
        _state.value = WearBridgeState.Idle
    }
}

sealed interface WearBridgeState {
    data object Idle : WearBridgeState
    data class Receiving(val sessionId: String) : WearBridgeState
    data class MissingFrames(val expected: Long, val received: Long) : WearBridgeState
    data class Finished(val sessionId: String) : WearBridgeState
}
