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
    private var currentLevel = 0f

    override fun start(file: File) {
        check(record == null) { "已经在录音" }

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
            while (running) {
                if (paused) {
                    Thread.sleep(20)
                    continue
                }
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_DEAD_OBJECT) {
                        break
                    }
                    continue
                }
                currentLevel = peakLevel(buffer, read)
                // read 一定是 2 的整数倍（16 bit 单声道），WavWriter 里有断言兜底
                wav.append(buffer, read)
            }
            currentLevel = 0f
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

    override fun pause() {
        paused = true
    }

    override fun resume() {
        paused = false
    }

    override fun stop(): Long {
        if (record == null) return 0
        running = false
        paused = false
        thread?.join(500)
        thread = null
        record?.let {
            runCatching { it.stop() }
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
