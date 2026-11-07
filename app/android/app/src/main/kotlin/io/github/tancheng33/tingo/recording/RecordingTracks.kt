package io.github.tancheng33.tingo.recording

import java.io.File

/**
 * 一场会议的音频文件到底在哪。
 *
 * 录制过程中写的是原始音轨（`mic.wav` / `system.wav`），正常结束时合成 `录音.wav`。
 * 进程被杀 / 服务被系统结束时，会议目录里只有原始音轨——如果转写与播放只认 `录音.wav`，
 * 这类会议就"看得见但用不了"，而规格要求它们可播放、可转写。
 *
 * 因此把"找音频"和"补合成"收在这里，转写、播放、启动修复都用同一套判断。
 */
object RecordingTracks {

    const val MIXED = TrackMerger.MIXED
    private val RAW = listOf(TrackMerger.MIC_TRACK, TrackMerger.SYSTEM_TRACK, "mixed.wav")

    /** 会议可用的音频文件；`录音.wav` 优先，其次才是残留的原始音轨。 */
    fun find(meetingDir: File): File? = (listOf(MIXED) + RAW)
        .map { File(meetingDir, it) }
        .firstOrNull { isComplete(it) }

    /**
     * 这个文件是不是一条完整的音轨：体积大于头，且头里的 data 长度与文件大小一致。
     *
     * 混音写到一半被杀会留下"头里长度还是 0"的 `录音.wav`；只看体积会把它当成完整录音，
     * 反而盖过旁边完好的 `mic.wav`。
     */
    private fun isComplete(file: File): Boolean {
        if (!file.isFile || file.length() <= WavWriter.HEADER_SIZE) return false
        val header = ByteArray(WavWriter.HEADER_SIZE)
        val read = file.inputStream().use { it.readNBytes(header, 0, header.size) }
        if (read < header.size) return false
        val declared = (header[40].toInt() and 0xFF) or
            ((header[41].toInt() and 0xFF) shl 8) or
            ((header[42].toInt() and 0xFF) shl 16) or
            ((header[43].toInt() and 0xFF) shl 24)
        val actual = (file.length() - WavWriter.HEADER_SIZE)
        return declared.toLong() == actual
    }
}
