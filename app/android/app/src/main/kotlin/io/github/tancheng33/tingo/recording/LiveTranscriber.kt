package io.github.tancheng33.tingo.recording

import io.github.tancheng33.tingo.transcription.AsrModels
import io.github.tancheng33.tingo.transcription.LiveAsr
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * 边录边转：把麦克风的实时样本按 VAD 切段，逐段用同一个离线识别模型转成文字。
 *
 * 为什么不是"流式识别模型"：桌面也是这么做的（`chunked.rs`）——用同一套离线模型按停顿切段，
 * 逐段出字。这样实时看到的分句与最终转写用的是同一份模型和同一套 VAD 参数，
 * 不会出现"边录时是一种切法、转写后是另一种"的两套结果。
 *
 * 线程模型：采集线程只调用 [offer]（不阻塞、不做推理）；推理在本类自己的线程里跑。
 * 识别跟不上时**丢新样本而不是排队**——录音本身不能因为转写慢而卡住。
 */
class LiveTranscriber(
    private val asr: LiveAsr,
    /** 关掉时释放模型（四个原生对象）；不传就只管线程，不管模型 */
    private val releaseModels: () -> Unit = {},
    private val sampleRate: Int = AsrModels.SAMPLE_RATE,
    /** 识别出一段话时回调（已加标点）。回调在推理线程上，界面要自己切主线程。 */
    private val onSentence: (String) -> Unit,
) {
    private val lock = Any()
    private var pending = FloatArray(0)
    private var closed = false
    private val running = AtomicBoolean(false)
    private val worker = Thread(
        {
            // 释放放在线程自己的收尾里：既不和还在跑的 recognize() 抢对象（use-after-free），
            // 也不会因为 join 超时把模型永远留在内存里
            try {
                loop()
            } finally {
                runCatching { releaseModels() }
            }
        },
        "tingo-live-asr",
    ).apply { isDaemon = true }

    /** 窗口大小：Silero VAD 要求固定 512 采样。 */
    private val window: Int = WINDOW_SAMPLES

    fun start() {
        running.set(true)
        worker.start()
    }

    /** 采集线程调用：把样本排进缓冲，立刻返回。 */
    fun offer(samples: FloatArray) {
        if (!running.get()) return
        synchronized(lock) {
            if (closed) return
            // 推理落后太多时宁可丢样本，也不能让录音线程等：录音是主线，实时文字是副产品
            if (pending.size > MAX_PENDING_SAMPLES) return
            pending = pending + samples
        }
    }

    /** 停止：把缓冲里剩下的话识别完，再退出线程（最多等 [STOP_TIMEOUT_MS]）。 */
    fun stop() {
        running.set(false)
        runCatching { worker.join(STOP_TIMEOUT_MS) }
    }

    /**
     * 标记关闭并停止线程。模型由推理线程自己在退出时释放（见 [worker]）。
     *
     * 每场录音都会新建一份 [AsrModels]（VAD/识别/标点/声纹四个原生对象）：不释放就会一场漏一份。
     */
    fun close() {
        synchronized(lock) { closed = true }
        stop()
    }

    private fun loop() {
        while (running.get()) {
            val batch = synchronized(lock) {
                if (pending.size < window) {
                    null
                } else {
                    val take = pending.size - (pending.size % window)
                    val head = pending.copyOfRange(0, take)
                    pending = pending.copyOfRange(take, pending.size)
                    head
                }
            }
            if (batch == null) {
                Thread.sleep(IDLE_SLEEP_MS)
                continue
            }
            feedVad(batch)
            drainSegments()
        }
        // 收尾：把尾巴喂给 VAD 并 flush，最后一段话不至于丢
        val tail = synchronized(lock) {
            val rest = pending
            pending = FloatArray(0)
            rest
        }
        // 尾巴不足一个窗口也要喂完：只在末尾补零到整窗再给 VAD，否则最后一两句话会静默丢掉。
        // 注意补的是"除以窗口的余数"，不是"一个窗口减总长度"——尾部常常比一个窗口长得多。
        if (tail.isNotEmpty()) {
            val remainder = tail.size % window
            val padded = if (remainder == 0) tail else tail + FloatArray(window - remainder)
            feedVad(padded)
        }
        runCatching { asr.flush() }
        drainSegments()
    }

    private fun feedVad(samples: FloatArray) {
        var offset = 0
        while (offset + window <= samples.size) {
            runCatching { asr.acceptWindow(samples.copyOfRange(offset, offset + window)) }
                .onFailure { return }
            offset += window
        }
    }

    private fun drainSegments() {
        while (asr.hasSegment()) {
            val speech = asr.popSegment()
            val durationMs = speech.size * 1000L / sampleRate
            // 太短的一段大概率是噪声：与转写管线同一个门限，实时文字不该比最终结果更花
            if (durationMs < AsrModels.MIN_SPEECH_MS) continue
            val text = runCatching { asr.recognize(speech) }.getOrDefault("").trim()
            if (text.isNotEmpty()) onSentence(text)
        }
    }

    companion object {
        /** 缓冲上限：约 30 秒的样本；超过就丢新来的，保住录音线程 */
        const val MAX_PENDING_SAMPLES = 16_000 * 30

        /** Silero VAD 的窗口长度：固定 512 采样 */
        const val WINDOW_SAMPLES = 512

        private const val IDLE_SLEEP_MS = 50L
        private const val STOP_TIMEOUT_MS = 3_000L
    }
}
