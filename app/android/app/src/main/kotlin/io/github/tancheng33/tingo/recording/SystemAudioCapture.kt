package io.github.tancheng33.tingo.recording

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import kotlin.concurrent.thread

/**
 * 系统音轨的抽象：录音状态机只依赖这五个动作，单测与设备测试可以用假实现。
 */
interface SystemAudioTrack {
    fun setSamplesListener(listener: ((FloatArray) -> Unit)?) {}
    fun alignTo(durationMs: Long) {}
    fun start(file: File)
    fun startAligned(file: File, positionMs: () -> Long) = start(file)
    fun pause()
    fun resume()
    fun peakLevel(): Float
    fun lastFailure(): Throwable? = null
    fun stop(): Long
}

/**
 * 抓"手机正在播放的声音"（会议软件的对方说话声）。
 *
 * 用的是 Android 10 起的 `AudioPlaybackCapture`：需要用户先授权屏幕录制，
 * 拿到 `MediaProjection` 才能建立捕获会话。
 *
 * 已知限制（写在这里而不是指望用户猜）：
 * - 应用可以在 manifest 里禁止被抓（`allowAudioPlaybackCapture=false`），会议软件普遍这么干；
 * - `USAGE_VOICE_COMMUNICATION` 这一类的通话音频按设计抓不到。
 * 所以这条通路只能"尽力而为"：抓不到就明说，不能假装录到了。
 */
class SystemAudioCapture(
    private val projection: MediaProjection,
) : SystemAudioTrack {
    private var record: AudioRecord? = null
    private var writer: WavWriter? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var failure: Throwable? = null
    @Volatile private var captureEpoch = 0

    /** 抓到的最大振幅：会议软件禁止被抓时系统给的是**静音**而不是异常。 */
    @Volatile private var peak = 0f
    private var worker: Thread? = null
    @Volatile private var samplesListener: ((FloatArray) -> Unit)? = null

    override fun setSamplesListener(listener: ((FloatArray) -> Unit)?) {
        samplesListener = listener
    }

    /** 开始把系统声音写进 [file]；失败抛异常，由调用方转成界面提示。 */
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun start(file: File) = startAligned(file) { 0L }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun startAligned(file: File, positionMs: () -> Long) {
        peak = 0f
        failure = null
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            // 会议软件的对方声音一般走 MEDIA；GAME/UNKNOWN 一并收着，能多抓一点是一点
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(CAPTURE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val bufferSize = AudioRecord.getMinBufferSize(
            CAPTURE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)

        val recorder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "系统声音捕获初始化失败" }

        val wav = WavWriter(file, CAPTURE_RATE, channels = 2)
        record = recorder
        writer = wav
        running = true
        recorder.startRecording()
        wav.padSilenceToDuration(positionMs())

        worker = thread(name = "tingo-system-audio", isDaemon = true) {
            val buffer = ByteArray(bufferSize)
            val converter = SystemPcmConverter()
            while (running) {
                if (paused) {
                    // 暂停时不读：这一段声音不写进文件，恢复后与麦克风那一路对得上
                    Thread.sleep(20)
                    continue
                }
                val epoch = captureEpoch
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    if (paused || epoch != captureEpoch) continue
                    var localPeak = 0f
                    var i = 0
                    while (i + 1 < read) {
                        val value = ((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort()
                        val amplitude = kotlin.math.abs(value.toInt()) / 32_768f
                        if (amplitude > localPeak) localPeak = amplitude
                        i += 2
                    }
                    if (localPeak > peak) peak = localPeak
                    val written = runCatching { wav.append(buffer, read) }
                    if (written.isFailure) {
                        failure = written.exceptionOrNull()
                        running = false
                        continue
                    }
                    samplesListener?.let { listener ->
                        runCatching { listener(converter.convert(buffer, read)) }
                    }
                } else if (read < 0 && running && !paused && epoch == captureEpoch) {
                    // 读失败（通常是投影被系统收回）：停下来，别写垃圾数据
                    failure = IllegalStateException("系统声音读取失败: $read")
                    running = false
                }
            }
        }
    }

    /** 抓到的最大振幅（0..1）。持续为 0 说明对方的声音根本没被抓到。 */
    override fun peakLevel(): Float = peak
    override fun lastFailure(): Throwable? = failure

    override fun alignTo(durationMs: Long) {
        writer?.padSilenceToDuration(durationMs)
    }

    /**
     * 暂停：与麦克风同步，别让两路时间轴错开。
     *
     * 只是不读还不够——AudioRecord 内部仍在缓冲，恢复后会把暂停期间的声音一起写进来，
     * 结果还是错位。所以这里真的停掉采集，恢复时重新开始。
     */
    override fun pause() {
        paused = true
        captureEpoch++
        runCatching { record?.stop() }
    }

    /** 继续采集。 */
    override fun resume() {
        if (!paused) return
        captureEpoch++
        runCatching { record?.startRecording() }.onFailure { failure = it }
        paused = false
    }

    /** 结束并回填 wav 头；返回写入的毫秒数。 */
    override fun stop(): Long {
        running = false
        // 先把 AudioRecord 停掉：采集线程可能正阻塞在 read() 里，光设标志位它出不来，
        // 而下面的 close() 一旦跑在它前面，就会"头长度小于实际数据"或者写入被吞
        runCatching { record?.stop() }
        worker?.join(2_000)
        worker = null
        runCatching { record?.release() }
        record = null
        val wav = writer
        writer = null
        val duration = wav?.let {
            // durationMs 已按"帧"换算（声道数算在内），不需要再除
            val ms = it.durationMs()
            runCatching { it.close() }
            ms
        } ?: 0L
        runCatching { projection.stop() }
        return duration
    }

    companion object {
        /** 系统混音的常见采样率；混音时会降到识别链用的 16 kHz。 */
        const val CAPTURE_RATE = 48_000
    }
}

/** Converts 48 kHz stereo PCM to 16 kHz mono while retaining phase across reads. */
class SystemPcmConverter {
    private var frameIndex = 0

    fun convert(buffer: ByteArray, length: Int): FloatArray {
        val result = ArrayList<Float>()
        var offset = 0
        while (offset + 3 < length) {
            if (frameIndex == 0) {
                val left = ((buffer[offset].toInt() and 0xff) or (buffer[offset + 1].toInt() shl 8)).toShort().toInt()
                val right = ((buffer[offset + 2].toInt() and 0xff) or (buffer[offset + 3].toInt() shl 8)).toShort().toInt()
                result.add((left + right) / 65536f)
            }
            frameIndex = (frameIndex + 1) % 3
            offset += 4
        }
        return result.toFloatArray()
    }
}
