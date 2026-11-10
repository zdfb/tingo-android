package io.github.tancheng33.tingo.transcription

/**
 * "边录边转"需要的最小能力：按窗口喂音频，取出一段段人声，逐段识别。
 *
 * 抽成接口是为了让切段/丢弃/回调这套逻辑能在 JVM 单测里跑——真实现要加载 sherpa 原生库，
 * 单测里跑不了。生产实现就是 [AsrModels]。
 */
interface LiveAsr {
    /** 喂一个固定长度的窗口（Silero VAD 要求 512 采样）。 */
    fun acceptWindow(window: FloatArray)

    /** VAD 是否攒出了一段人声。 */
    fun hasSegment(): Boolean

    /** 取出一段人声的样本。 */
    fun popSegment(): FloatArray

    /** 把缓冲里剩下的音频当作"说完了"处理。 */
    fun flush()

    /** 识别一段人声（已加标点）。 */
    fun recognize(samples: FloatArray): String
}
