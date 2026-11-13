package io.github.tancheng33.tingo.recording

import java.io.File

/**
 * 录音器的抽象。
 *
 * 界面与 ViewModel 只依赖这个接口：真机上是 [MicRecorder]，单测里是假实现——
 * 这样"开始/暂停/继续/结束"的状态流转不必依赖真麦克风就能验证。
 */
interface AudioCapture {
    fun setSamplesListener(listener: ((FloatArray) -> Unit)?) {}
    /** 打开麦克风并把 PCM 追加写入 [file]；失败时抛异常。 */
    fun start(file: File)

    /** 暂停：停止读取，但保持文件与编码参数。 */
    fun pause()

    /** 继续：从上次停止的位置接着写。 */
    fun resume()

    /** 结束并回填 wav 头；返回实际写入的毫秒数。 */
    fun stop(): Long

    /** 采集过程中遇到的致命错误（磁盘满之类）；界面据此提示，而不是假装还在录。 */
    fun lastFailure(): Throwable? = null

    /** Audio frames already persisted, excluding wall time spent paused. */
    fun durationMs(): Long = 0

    /** 当前电平（0..1），用于界面上的波形/音量提示。 */
    fun level(): Float
}
