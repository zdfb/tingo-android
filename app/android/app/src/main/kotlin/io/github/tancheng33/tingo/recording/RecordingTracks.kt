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

    /** 会议目录里所有完整音轨（归档用：两路都留着时两路都要带走）。 */
    fun audioTracks(meetingDir: File): List<File> =
        (listOf(MIXED) + RAW).map { File(meetingDir, it) }.filter { isComplete(it) }

    /**
     * 转写前把原始音轨合成 `录音.wav`（如果还没有）。
     *
     * 转写要的是 16 kHz 单声道，而 `system.wav` 是 48 kHz 立体声；两路都在时还必须把
     * 对方的声音混进去，否则转写出来只有本机一侧。混音会整段读入，所以只在用户真正点了
     * "转写"时做（IO 协程里），绝不在启动路径上做。
     *
     * @return 合成出的文件；已有 `录音.wav` 或没有音轨时返回 null
     */
    fun ensureMixed(meetingDir: File): File? {
        val mixed = File(meetingDir, MIXED)
        if (isComplete(mixed)) return null
        val raw = RAW.map { File(meetingDir, it) }.filter { isComplete(it) }
        if (raw.isEmpty()) return null
        // 即使是单路麦克风也合成 录音.wav：桌面版的约定就是"一场会议的录音叫 录音.wav"，
        // 统一了文件名，导出、播放、转写才不必各写一套判断
        return TrackMerger.mixInto(meetingDir)
    }

    /**
     * 会议可用的音频文件；`录音.wav` 优先，其次才是残留的原始音轨，最后是导入/示例带来的原始格式。
     *
     * 导入与示例会议存的可能是 m4a（桌面端本来就是"保留原文件"）。
     * 只认 wav 会让这类会议"看得见、点不了播放"——示例会议正是这样被漏掉的。
     */
    fun find(meetingDir: File): File? =
        (listOf(MIXED) + RAW).map { File(meetingDir, it) }.firstOrNull { isComplete(it) }
            ?: importedAudio(meetingDir)

    /** 可以交给播放器的格式（MediaPlayer 都支持）。 */
    private val PLAYABLE = setOf("wav", "m4a", "mp3", "mp4", "aac", "flac", "ogg", "webm", "mov", "mkv")

    /** 会议目录里任何可播放的音频（跳过原始音轨：那几路要么已合成，要么不完整）。 */
    private fun importedAudio(meetingDir: File): File? = meetingDir.listFiles().orEmpty()
        .filter { file ->
            file.isFile &&
                file.name !in RAW &&
                file.name != MIXED &&
                file.extension.lowercase() in PLAYABLE
        }
        .maxByOrNull { it.lastModified() }

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
