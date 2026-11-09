package io.github.tancheng33.tingo.importing

/**
 * 把解码出来的任意采样率、任意声道的 PCM 变成引擎要的「16 kHz 单声道」。
 *
 * ASR 模型是按 16 kHz 单声道训练的：采样率不对，识别出来就是一片含糊；
 * 声道不合并，系统声那一路会被直接丢掉一半。这两步是纯计算，单独放这里便于单测。
 */
object AudioResample {

    /** 本机 ASR 要求的目标采样率。 */
    const val TARGET_RATE = 16_000

    /** 多声道取均值（左右声道的内容都要留下，取左或取右都会丢东西）。 */
    fun toMono(interleaved: ShortArray, channels: Int): FloatArray {
        require(channels >= 1) { "声道数不合法：$channels" }
        val frames = interleaved.size / channels
        val mono = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            for (channel in 0 until channels) {
                sum += interleaved[frame * channels + channel] / 32768f
            }
            mono[frame] = sum / channels
        }
        return mono
    }

    /**
     * 线性插值重采样。
     *
     * 素材大多是 44.1k / 48k，降到 16k 用最近邻会有明显混叠（哨音）；
     * 线性插值在这个场景够用，且不引入新依赖。
     */
    fun resample(samples: FloatArray, fromRate: Int, toRate: Int = TARGET_RATE): FloatArray {
        require(fromRate > 0) { "采样率不合法：$fromRate" }
        if (fromRate == toRate || samples.isEmpty()) return samples
        val ratio = fromRate.toDouble() / toRate
        val outLength = (samples.size / ratio).toInt()
        val out = FloatArray(outLength)
        for (index in 0 until outLength) {
            val position = index * ratio
            val left = position.toInt().coerceAtMost(samples.size - 1)
            val right = (left + 1).coerceAtMost(samples.size - 1)
            val weight = (position - left).toFloat()
            out[index] = samples[left] * (1f - weight) + samples[right] * weight
        }
        return out
    }

    /** 转成 16 bit PCM（wav 的样本格式）。 */
    fun toPcm16(samples: FloatArray): ShortArray = ShortArray(samples.size) { index ->
        (samples[index] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
    }
}
