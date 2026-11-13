package io.github.tancheng33.tingo.recording

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import kotlin.math.abs

/**
 * 麦克风录音：16 kHz / 单声道 / 16 bit PCM，直接写 wav。
 *
 * 用 `VOICE_RECOGNITION` 音源而不是 `MIC`：它关掉了部分针对通话的自动增益与降噪，
 * 对识别更友好。读取在独立线程里跑，主线程只负责开关。
 */
class MicRecorder : AudioCapture {

    private var record: AudioRecord? = null
    private var writer: WavWriter? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    @Volatile
    private var captureEpoch = 0

    /** 采集线程里遇到的致命错误（磁盘满、写入被关闭）；stop() 之后由调用方查。 */
    @Volatile
    var failed: Throwable? = null
        private set

    private var currentLevel = 0f

    /**
     * 实时样本回调（16 bit 单声道 → [-1,1]）。边录边转用它。
     *
     * 在采集线程上调用：实现必须立刻返回，不许在这里做推理。
     * 默认 null，录音本身不依赖它。
     */
    @Volatile
    var onSamples: ((FloatArray) -> Unit)? = null

    override fun setSamplesListener(listener: ((FloatArray) -> Unit)?) {
        onSamples = listener
    }

    override fun start(file: File) {
        check(record == null) { "已经在录音" }
        // 上一次的错误不能带到这一次：否则一次写盘失败之后，每次结束录音都报"中断"
        failed = null

        val minBuffer = AudioRecord.getMinBufferSize(
            WavWriter.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = if (minBuffer > 0) minBuffer * 2 else 8_192

        val recorder = createRecorder(bufferBytes)
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }

        val wav = WavWriter(file)
        record = recorder
        writer = wav
        running = true
        paused = false
        recorder.startRecording()

        thread = Thread({
            val buffer = ByteArray(bufferBytes)
            // 磁盘写满、或 stop() 在 join 超时后关掉了 writer，append 都会抛异常。
            // 在采集线程上抛 = 整个进程被杀（录音直接丢），所以就地兜住并结束这一轮采集。
            try {
            while (running) {
                if (paused) {
                    Thread.sleep(20)
                    continue
                }
                val epoch = captureEpoch
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_DEAD_OBJECT) {
                        if (running && !paused && epoch == captureEpoch) {
                            failed = IllegalStateException("麦克风读取失败: $read")
                            running = false
                            break
                        }
                    }
                    continue
                }
                if (paused || epoch != captureEpoch) continue
                currentLevel = peakLevel(buffer, read)
                // read 一定是 2 的整数倍（16 bit 单声道），WavWriter 里有断言兜底
                wav.append(buffer, read)
                onSamples?.let { listener ->
                    runCatching { listener(toFloats(buffer, read)) }
                }
            }
            } catch (e: Exception) {
                // 写不下去就停：已写入的部分仍然是一份可修复的 wav（启动时会补头）
                failed = e
                running = false
            } finally {
                currentLevel = 0f
            }
        }, "tingo-mic").apply { isDaemon = true; start() }
    }

    @SuppressLint("MissingPermission") // 调用方负责在请求过 RECORD_AUDIO 之后才进来
    private fun createRecorder(bufferBytes: Int): AudioRecord = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        WavWriter.SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        bufferBytes,
    )

    /** 16 bit 小端 PCM → 归一化浮点（sherpa 要的输入）。 */
    private fun toFloats(buffer: ByteArray, length: Int): FloatArray {
        val count = length / 2
        val out = FloatArray(count)
        for (i in 0 until count) {
            val lo = buffer[2 * i].toInt() and 0xFF
            val hi = buffer[2 * i + 1].toInt()
            val sample = (hi shl 8) or lo
            out[i] = sample.toShort().toFloat() / 32_768f
        }
        return out
    }

    override fun pause() {
        paused = true
        captureEpoch++
        runCatching { record?.stop() }
    }

    override fun resume() {
        captureEpoch++
        runCatching { record?.startRecording() }.onFailure { failed = it }
        paused = false
    }

    override fun durationMs(): Long = writer?.durationMs() ?: 0

    override fun stop(): Long {
        if (record == null) return 0
        running = false
        paused = false
        runCatching { record?.stop() }
        thread?.join(500)
        thread = null
        record?.let {
            it.release()
        }
        record = null
        val wav = writer
        writer = null
        if (wav == null) return 0
        val duration = wav.durationMs()
        wav.close()
        return duration
    }

    override fun lastFailure(): Throwable? = failed

    override fun level(): Float = currentLevel

    /** 取这一段的峰值电平，映射到 0..1，界面用来画音量条。 */
    private fun peakLevel(buffer: ByteArray, length: Int): Float {
        var peak = 0
        var i = 0
        // 每两个字节是一个小端 16 bit 样本
        while (i + 1 < length) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
            peak = maxOf(peak, abs(sample))
            i += 2
        }
        return peak / 32_767f
    }
}
