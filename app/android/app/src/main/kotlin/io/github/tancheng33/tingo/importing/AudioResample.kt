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

    /** 多声道取均值（左右两声道的内容都要留下，取左或取右都会丢东西）。 */
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

    /** 一次性重采样（测试与短片段用；长录音走 [StreamingResampler]）。 */
    fun resample(samples: FloatArray, fromRate: Int, toRate: Int = TARGET_RATE): FloatArray {
        require(fromRate > 0) { "采样率不合法：$fromRate" }
        if (fromRate == toRate || samples.isEmpty()) return samples
        val stream = StreamingResampler(fromRate, toRate)
        return stream.push(samples)
    }

    /**
     * 分块重采样。
     *
     * 一小时的 48 kHz 录音整段进内存要一个多 G，所以解码是一块一块来的；
     * 块与块之间要留一个样本做插值，否则每块首尾会有一声轻响。
     *
     * 说明：线性插值不做前置低通，8 kHz 以上的分量会有混叠。这个场景（语音转写）
     * 里可以接受；真介意就得换成带滤波的重采样器。
     */
    class StreamingResampler(private val fromRate: Int, private val toRate: Int = TARGET_RATE) {
        private val ratio = fromRate.toDouble() / toRate
        /** 上一块最后一个样本：下一块插值时要用它当"左边那个点" */
        private var carry: Float? = null
        /** 下一个输出样本在「carry + 本块」里的位置 */
        private var position = 0.0

        init {
            require(fromRate > 0) { "采样率不合法：$fromRate" }
        }

        /** 喂一块样本，返回这一块能确定的输出。 */
        fun push(samples: FloatArray): FloatArray {
            if (fromRate == toRate) return samples
            // 前半段要靠上一块的最后一个样本才能插值
            val input = carry?.let { previous -> FloatArray(samples.size + 1).also { it[0] = previous; samples.copyInto(it, 1) } }
                ?: samples
            val out = ArrayList<Float>(input.size * toRate / fromRate + 1)
            while (position <= input.size - 2) {
                val index = position.toInt()
                val weight = (position - index).toFloat()
                out += input[index] * (1f - weight) + input[index + 1] * weight
                position += ratio
            }
            carry = input.last()
            // 位置换算到「只有 carry 一个点」的坐标系里，下一块接着算
            position -= input.size - 1
            return out.toFloatArray()
        }

        /** 收尾：末尾不足一个插值区间，少一个样本听不出来，直接结束。 */
        fun flush(): FloatArray = FloatArray(0)
    }

    /** 转成 16 bit PCM（wav 的样本格式）。 */
    fun toPcm16(samples: FloatArray): ShortArray = ShortArray(samples.size) { index ->
        (samples[index] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
    }
}
