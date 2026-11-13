package io.github.tancheng33.tingo.wear

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import io.github.tancheng33.tingo.TingoApplication
import io.github.zdfb.tingo.wear.protocol.WearControlAction
import io.github.zdfb.tingo.wear.protocol.WearControlCodec
import io.github.zdfb.tingo.wear.protocol.WearFrameDecoder
import java.io.InputStream
import kotlin.concurrent.thread

/** 手机端 Wear OS Data Layer 入口；长连接读取放到独立线程，不阻塞系统回调。 */
class WearDataLayerService : WearableListenerService() {
    private val bridge: WearAudioBridge
        get() = (application as TingoApplication).container.wearAudioBridge

    override fun onMessageReceived(event: MessageEvent) {
        runCatching { WearControlCodec.decode(event.data) }
            .onSuccess { message ->
                when (message.action) {
                    WearControlAction.START -> bridge.begin(message.sessionId)
                    WearControlAction.STOP -> bridge.finish(message.sessionId)
                    else -> Unit
                }
            }
            .onFailure { Log.w(TAG, "忽略无效的手表控制消息", it) }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        thread(isDaemon = true, name = "tingo-wear-receiver") {
            runCatching {
                val input = Tasks.await(Wearable.getChannelClient(this).getInputStream(channel))
                readFrames(input)
            }.onFailure { Log.w(TAG, "手表音频通道断开", it) }
        }
    }

    private fun readFrames(input: InputStream) {
        input.use {
            val decoder = WearFrameDecoder()
            val bytes = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                decoder.offer(bytes.copyOf(count)).forEach { bridge.accept(it) }
            }
        }
    }

    companion object {
        private const val TAG = "TingoWear"
    }
}
