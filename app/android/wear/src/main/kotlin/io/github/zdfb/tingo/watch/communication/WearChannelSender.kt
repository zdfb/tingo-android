package io.github.zdfb.tingo.watch.communication

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import io.github.zdfb.tingo.wear.protocol.WearAudioFrame
import io.github.zdfb.tingo.wear.protocol.WearControlAction
import io.github.zdfb.tingo.wear.protocol.WearControlCodec
import io.github.zdfb.tingo.wear.protocol.WearControlMessage
import io.github.zdfb.tingo.wear.protocol.WearFrameDecoder
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Wear OS 端的连接适配器；录音线程只调用 [send]，不等待节点发现。 */
class WearChannelSender(context: Context) {
    private val appContext = context.applicationContext
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tingo-watch-link").apply { isDaemon = true }
    }
    private val lock = Any()
    private var output: OutputStream? = null
    private var channel: ChannelClient.Channel? = null
    private var nodeId: String? = null
    @Volatile private var currentSessionId: String? = null

    fun open(sessionId: String) {
        currentSessionId = sessionId
        worker.execute {
            runCatching {
                val node = Tasks.await(Wearable.getNodeClient(appContext).connectedNodes).firstOrNull()
                    ?: return@runCatching
                nodeId = node.id
                Tasks.await(
                    Wearable.getMessageClient(appContext).sendMessage(
                        node.id,
                        CONTROL_PATH,
                        WearControlCodec.encode(WearControlMessage(sessionId, WearControlAction.START)),
                    ),
                )
                val opened = Tasks.await(Wearable.getChannelClient(appContext).openChannel(node.id, CHANNEL_PATH))
                val stream = Tasks.await(Wearable.getChannelClient(appContext).getOutputStream(opened))
                synchronized(lock) {
                    channel = opened
                    output = stream
                }
            }
        }
    }

    fun send(frame: WearAudioFrame): Boolean = synchronized(lock) {
        val stream = output ?: return false
        runCatching {
            stream.write(WearFrameDecoder.encode(frame))
            stream.flush()
        }.isSuccess
    }

    fun close() {
        val currentNode = nodeId
        val session = currentSessionId
        if (currentNode != null && session != null) {
            runCatching {
                Tasks.await(
                    Wearable.getMessageClient(appContext).sendMessage(
                        currentNode,
                        CONTROL_PATH,
                        WearControlCodec.encode(WearControlMessage(session, WearControlAction.STOP)),
                    ),
                )
            }
        }
        synchronized(lock) {
            runCatching { output?.close() }
            output = null
            channel = null
            nodeId = null
        }
        worker.shutdownNow()
    }

    companion object {
        const val CHANNEL_PATH = "/tingo/recording"
        const val CONTROL_PATH = "/tingo/control"
    }
}
