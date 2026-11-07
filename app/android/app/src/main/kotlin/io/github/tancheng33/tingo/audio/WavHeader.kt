package io.github.tancheng33.tingo.audio

/** 一个只有头的 wav 没有可播的内容；比这个还小就当没有录音。 */
object WavHeader {
    const val MIN_BYTES = 44L + 1
}
