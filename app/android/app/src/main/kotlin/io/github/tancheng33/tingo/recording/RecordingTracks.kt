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
    fun find(meetingDir: File): File? {
        val mixed = File(meetingDir, MIXED)
        if (mixed.isFile && mixed.length() > WavWriter.HEADER_SIZE) return mixed
        return RAW.map { File(meetingDir, it) }
            .firstOrNull { it.isFile && it.length() > WavWriter.HEADER_SIZE }
    }

    /**
     * 只有原始音轨时补一个 `录音.wav`，让这场会议回到"正常结束"的状态。
     *
     * @return 合成出的文件；本来就有 `录音.wav` 或没有音轨时返回 null
     */
    fun ensureMixed(meetingDir: File): File? {
        val mixed = File(meetingDir, MIXED)
        if (mixed.isFile && mixed.length() > WavWriter.HEADER_SIZE) return null
        return TrackMerger.mixInto(meetingDir)
    }
}
