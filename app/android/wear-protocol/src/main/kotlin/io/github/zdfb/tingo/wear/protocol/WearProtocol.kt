package io.github.zdfb.tingo.wear.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 手机与手表之间共享的音频帧协议。
 *
 * 音频走 ChannelClient 的连续字节流，所以每帧必须自带长度和会话信息；
 * 控制消息仍由两端各自的 MessageClient 适配层承载。
 */
data class WearAudioFrame(
    val sessionId: String,
    val sequence: Long,
    val sampleRate: Int,
    val channels: Int,
    val samples: ShortArray,
)

enum class WearControlAction {
    START,
    PAUSE,
    RESUME,
    STOP,
    REQUEST_MISSING,
    STATUS,
}

data class WearControlMessage(
    val sessionId: String,
    val action: WearControlAction,
    val detail: String = "",
)

object WearControlCodec {
    private const val VERSION = "1"

    fun encode(message: WearControlMessage): ByteArray {
        val session = Base64.getUrlEncoder().withoutPadding().encodeToString(message.sessionId.toByteArray(StandardCharsets.UTF_8))
        val detail = Base64.getUrlEncoder().withoutPadding().encodeToString(message.detail.toByteArray(StandardCharsets.UTF_8))
        return (VERSION + "|" + message.action.name + "|" + session + "|" + detail).toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(bytes: ByteArray): WearControlMessage {
        val parts = String(bytes, StandardCharsets.UTF_8).split('|', limit = 4)
        require(parts.size == 4 && parts[0] == VERSION) { "无效的 Wear 控制消息" }
        val action = runCatching { WearControlAction.valueOf(parts[1]) }.getOrElse {
            throw IllegalArgumentException("未知的 Wear 控制动作", it)
        }
        val session = String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8)
        val detail = String(Base64.getUrlDecoder().decode(parts[3]), StandardCharsets.UTF_8)
        require(session.isNotBlank()) { "sessionId 不能为空" }
        return WearControlMessage(session, action, detail)
    }
}

sealed interface WearSequenceResult {
    data object Accepted : WearSequenceResult
    data object Duplicate : WearSequenceResult
    data class Gap(val expected: Long, val received: Long) : WearSequenceResult
}

class WearFrameDecoder {
    private var pending = ByteArray(0)

    /** 追加任意大小的网络块，返回已经完整解出的帧。 */
    fun offer(bytes: ByteArray): List<WearAudioFrame> {
        if (bytes.isEmpty()) return emptyList()
        pending += bytes
        val frames = mutableListOf<WearAudioFrame>()
        while (true) {
            if (pending.size < FIXED_HEADER_BYTES) return frames
            val buffer = ByteBuffer.wrap(pending).order(ByteOrder.BIG_ENDIAN)
            if (buffer.int != MAGIC || buffer.get() != VERSION || buffer.get() != AUDIO_KIND) {
                throw IllegalArgumentException("无效的 Wear 音频帧头")
            }
            val sessionLength = buffer.short.toInt() and 0xffff
            val payloadLength = buffer.int
            if (sessionLength !in 1..MAX_SESSION_ID_BYTES || payloadLength !in 0..MAX_PAYLOAD_BYTES) {
                throw IllegalArgumentException("Wear 音频帧长度越界")
            }
            val total = HEADER_BYTES + sessionLength + payloadLength
            if (pending.size < total) return frames
            val frameBytes = pending.copyOfRange(0, total)
            frames += decode(frameBytes)
            pending = pending.copyOfRange(total, pending.size)
        }
    }

    fun clear() {
        pending = ByteArray(0)
    }

    companion object {
        private const val FIXED_HEADER_BYTES = 4 + 1 + 1 + 2 + 4
        private const val FRAME_METADATA_BYTES = 8 + 4 + 2 + 4
        private const val HEADER_BYTES = FIXED_HEADER_BYTES + FRAME_METADATA_BYTES
        private const val MAGIC = 0x54474F31
        private const val VERSION: Byte = 1
        private const val AUDIO_KIND: Byte = 1
        private const val MAX_SESSION_ID_BYTES = 128
        private const val MAX_PAYLOAD_BYTES = 256 * 1024

        fun encode(frame: WearAudioFrame): ByteArray {
            require(frame.sessionId.isNotBlank()) { "sessionId 不能为空" }
            require(frame.sessionId.toByteArray(StandardCharsets.UTF_8).size <= MAX_SESSION_ID_BYTES) { "sessionId 太长" }
            require(frame.sequence >= 0) { "sequence 不能为负数" }
            require(frame.sampleRate > 0) { "sampleRate 必须为正数" }
            require(frame.channels in 1..2) { "channels 必须为 1 或 2" }
            val session = frame.sessionId.toByteArray(StandardCharsets.UTF_8)
            val payload = ByteBuffer.allocate(frame.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                frame.samples.forEach(::putShort)
            }.array()
            require(payload.size <= MAX_PAYLOAD_BYTES) { "音频帧过大" }
            return ByteBuffer.allocate(HEADER_BYTES + session.size + payload.size)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(MAGIC)
                .put(VERSION)
                .put(AUDIO_KIND)
                .putShort(session.size.toShort())
                .putInt(payload.size)
                .put(session)
                .putLong(frame.sequence)
                .putInt(frame.sampleRate)
                .putShort(frame.channels.toShort())
                .putInt(frame.samples.size)
                .put(payload)
                .array()
        }

        fun decode(bytes: ByteArray): WearAudioFrame {
            require(bytes.size >= HEADER_BYTES) { "Wear 音频帧过短" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            require(buffer.int == MAGIC && buffer.get() == VERSION && buffer.get() == AUDIO_KIND) { "无效的 Wear 音频帧头" }
            val sessionLength = buffer.short.toInt() and 0xffff
            val payloadLength = buffer.int
            require(sessionLength in 1..MAX_SESSION_ID_BYTES && payloadLength in 0..MAX_PAYLOAD_BYTES) { "Wear 音频帧长度越界" }
            require(bytes.size == HEADER_BYTES + sessionLength + payloadLength) { "Wear 音频帧未完整到达" }
            val sessionBytes = ByteArray(sessionLength)
            buffer.get(sessionBytes)
            val sequence = buffer.long
            val sampleRate = buffer.int
            val channels = buffer.short.toInt()
            val sampleCount = buffer.int
            require(sampleRate > 0 && channels in 1..2 && sampleCount >= 0 && sampleCount * 2 == payloadLength) { "Wear 音频帧参数无效" }
            val payload = ByteArray(payloadLength)
            buffer.get(payload)
            val samples = ShortArray(sampleCount)
            ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
            return WearAudioFrame(String(sessionBytes, StandardCharsets.UTF_8), sequence, sampleRate, channels, samples)
        }
    }
}

class WearSequenceTracker {
    private var next = 0L

    fun accept(sequence: Long): WearSequenceResult = when {
        sequence < next -> WearSequenceResult.Duplicate
        sequence > next -> WearSequenceResult.Gap(next, sequence)
        else -> {
            next++
            WearSequenceResult.Accepted
        }
    }

    fun reset() {
        next = 0L
    }
}
