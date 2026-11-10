package io.github.tancheng33.tingo.recording

import io.github.tancheng33.tingo.transcription.WavReader
import java.io.File
import kotlin.math.roundToInt

/**
 * 音轨样本与 wav 之间的唯一样式约定。
 *
 * 这里只留**真的在用**的那一半：`samplesToBytes`（[StreamingMixer] 写盘要用）。
 * 降声道与重采样在 `AudioResample`（导入解码那一路），整段混音在 [StreamingMixer]
 * ——早先这里还有一套"整段读入再算"的等价实现，长录音会 OOM，已经删掉了。
 */
object AudioMix {

    const val TARGET_RATE = 16_000

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
    fun mixInto(meetingDir: File): File? {
        val mic = File(meetingDir, MIC_TRACK)
        val system = File(meetingDir, SYSTEM_TRACK)
        val mixed = File(meetingDir, MIXED)

        val sources = listOf(mic, system).filter { it.isFile && it.length() > WavReader.HEADER_BYTES }
        if (sources.isEmpty()) return null

        // 先写临时文件再改名：中途被杀时不会留下一个"头里长度为 0"的 录音.wav，
        // 那种半截文件会盖过旁边完好的 mic.wav / system.wav。
        // 临时名必须唯一：结束录音后的后台混音与转写前的混音可能同时在写同一个目录，
        // 共用一个 .tmp 会互相截断。
        val temp = File.createTempFile("mix-", ".tmp", meetingDir)
        // 逐块混：一小时双轨整段读入要几百 MB 浮点，必然 OOM（规格明确禁止）。
        // 混音可能中途抛异常（音轨头损坏等）：无论成败都要关掉 writer，并删掉临时文件，
        // 否则会在会议目录留下一个与录音同样大的孤儿文件。
        val ok = runCatching {
            val writer = WavWriter(temp, AudioMix.TARGET_RATE)
            try {
                StreamingMixer.mix(sources, writer)
            } finally {
                writer.close()
            }
        }.isSuccess
        if (!ok || !temp.renameTo(mixed)) {
            temp.delete()
            return null
        }
        return mixed
    }

}
