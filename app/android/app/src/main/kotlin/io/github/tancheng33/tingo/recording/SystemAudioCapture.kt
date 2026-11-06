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
) {
    private var record: AudioRecord? = null
    private var writer: WavWriter? = null
    @Volatile private var running = false
    private var worker: Thread? = null

    /** 开始把系统声音写进 [file]；失败抛异常，由调用方转成界面提示。 */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun start(file: File) {
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

        worker = thread(name = "tingo-system-audio", isDaemon = true) {
            val buffer = ByteArray(bufferSize)
            while (running) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    runCatching { wav.append(buffer, read) }
                } else if (read < 0) {
                    // 读失败（通常是投影被系统收回）：停下来，别写垃圾数据
                    running = false
                }
            }
        }
    }

    /** 结束并回填 wav 头；返回写入的毫秒数。 */
    fun stop(): Long {
        running = false
        worker?.join(1_000)
        worker = null
        runCatching { record?.stop() }
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
