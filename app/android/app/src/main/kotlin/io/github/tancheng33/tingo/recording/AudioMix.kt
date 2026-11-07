package io.github.tancheng33.tingo.recording

import java.io.File
import kotlin.math.roundToInt

/**
 * 原始音轨的处理：重采样、混音、写 wav。
 *
 * 为什么需要：`AudioPlaybackCapture` 拿到的是设备混音出来的 48 kHz 立体声，
 * 而我们的识别链是 16 kHz 单声道；电麦克风那一路本来就是 16 kHz 单声道。
 * 两路要合成一条 `录音.wav` 才能给引擎当"这场会议的录音"。
 *
 * 这些函数只碰数字，所以能在 JVM 单测里逐样本核对——真机上的采集没法自动化，
 * 至少"混出来的波形对不对"要能被测住。
 */
object AudioMix {

    const val TARGET_RATE = 16_000

    /** 16 bit 小端字节 → 归一化浮点。 */
    fun bytesToSamples(bytes: ByteArray, count: Int = bytes.size / 2): FloatArray {
        val samples = FloatArray(count)
        for (i in 0 until count) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            samples[i] = ((hi shl 8) or lo).toShort() / 32_768f
        }
        return samples
    }

    /** 归一化浮点 → 16 bit 小端字节。 */
    fun samplesToBytes(samples: FloatArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, sample ->
            val value = (sample.coerceIn(-1f, 1f) * 32_767f).roundToInt()
            bytes[i * 2] = (value and 0xFF).toByte()
            bytes[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /**
     * 立体声（交织）降到单声道：两侧取平均。
     *
     * 直接丢右声道会让只出现在右声道的发言人消失，所以必须混。
     */
    fun stereoToMono(samples: FloatArray): FloatArray =
        FloatArray(samples.size / 2) { i -> (samples[i * 2] + samples[i * 2 + 1]) / 2f }

    /**
     * 线性插值重采样到 [TARGET_RATE]。
     *
     * 会议录音的目标是听得清、识别得准，不需要高保真：降采样时线性插值足够，
     * 重要的是不要引入 DC 偏移或音量骤降。
     */
    fun resample(samples: FloatArray, fromRate: Int, toRate: Int = TARGET_RATE): FloatArray {
        if (fromRate == toRate || samples.isEmpty()) return samples
        val ratio = fromRate.toDouble() / toRate
        val outLength = (samples.size / ratio).toInt()
        return FloatArray(outLength) { i ->
            val position = i * ratio
            val lower = position.toInt()
            val upper = (lower + 1).coerceAtMost(samples.size - 1)
            val weight = (position - lower).toFloat()
            samples[lower] * (1 - weight) + samples[upper] * weight
        }
    }

    /**
     * 把若干音轨按样本相加，超出范围就削顶。
     *
     * 相加而不是取平均：桌面版也是叠加，取平均会把只有一方说话时的音量压掉一半。
     */
    fun mix(tracks: List<FloatArray>): FloatArray {
        if (tracks.isEmpty()) return FloatArray(0)
        val length = tracks.maxOf { it.size }
        val mixed = FloatArray(length)
        tracks.forEach { track ->
            for (i in track.indices) mixed[i] += track[i]
        }
        for (i in mixed.indices) mixed[i] = mixed[i].coerceIn(-1f, 1f)
        return mixed
    }
}

/**
 * 两路原始音轨（麦克风 + 系统内录）合成 `录音.wav`。
 *
 * 桌面版的约定是 `mic.wav` + `system.wav` → `录音.*`；Android 沿用同一套文件名，
 * 这样手机录的会议和电脑录的会议在引擎眼里是同一种东西。
 */
object TrackMerger {

    const val MIC_TRACK = "mic.wav"
    const val SYSTEM_TRACK = "system.wav"
    const val MIXED = "录音.wav"

    /**
     * @return 合成后的文件；两路都不存在时返回 null
     */
    fun mixInto(meetingDir: File, systemSampleRate: Int = 48_000): File? {
        val mic = File(meetingDir, MIC_TRACK)
        val system = File(meetingDir, SYSTEM_TRACK)
        val mixed = File(meetingDir, MIXED)

        val micSamples = mic.takeIf { it.isFile }?.let { readTrack(it, 16_000) }
        val systemSamples = system.takeIf { it.isFile }?.let { readTrack(it, systemSampleRate) }
        val tracks = listOfNotNull(micSamples, systemSamples)
        if (tracks.isEmpty()) return null

        // 先写临时文件再改名：中途被杀时不会留下一个"头里长度为 0"的 录音.wav，
        // 那种半截文件会盖过旁边完好的 mic.wav / system.wav。
        // 临时名必须唯一：结束录音后的后台混音与转写前的混音可能同时在写同一个目录，
        // 共用一个 .tmp 会互相截断。
        val temp = File.createTempFile("mix-", ".tmp", meetingDir)
        WavWriter(temp, AudioMix.TARGET_RATE).apply {
            append(AudioMix.samplesToBytes(AudioMix.mix(tracks)))
            close()
        }
        if (!temp.renameTo(mixed)) {
            temp.delete()
            return null
        }
        return mixed
    }

    private fun readTrack(file: File, expectedRate: Int): FloatArray {
        // 系统音轨是立体声，宽容读取会先降到单声道
        val pcm = io.github.tancheng33.tingo.transcription.WavReader.readAnyChannels(file)
        val rate = if (pcm.sampleRate > 0) pcm.sampleRate else expectedRate
        return AudioMix.resample(pcm.samples, rate)
    }
}
