package io.github.tancheng33.tingo.recording

import java.io.File

/**
 * 启动时修复"没来得及收尾"的录音。
 *
 * 进程被杀 / 前台服务被系统结束时，wav 头里的长度字段还是 0，文件能播但时长显示为 0，
 * 转写也读不出样本。这里把这类文件的头补好，用户下次打开就只见一场正常会议，
 * 而不是一个"点不开"的条目。
 *
 * 只补头、不在启动时混音：混音要整段读入音轨（一小时两轨就是几百兆），放在启动线程上
 * 会 OOM，也容易和用户正在做的转写抢同一个目录。转写与播放通过 [RecordingTracks.find]
 * 回落到 `mic.wav` / `system.wav`，所以"可播放、可转写"不依赖混音。
 *
 * @return 修好的文件数
 */
object InterruptedRecordings {

    /**
     * 需要检查的文件名。
     *
     * 录制过程中写的是原始音轨（`mic.wav`、`system.wav`），只有正常结束时才合成
     * `录音.wav`；进程被杀时留下的是前两个，所以它们才是这条恢复的主要对象。
     */
    private val REPAIRABLE_NAMES = listOf("mic.wav", "system.wav", "录音.wav")

    fun repairUnder(meetingsRoot: File): Int {
        val dirs = meetingsRoot.listFiles { file -> file.isDirectory } ?: return 0
        var repaired = 0
        dirs.forEach { dir ->
            REPAIRABLE_NAMES.forEach { name ->
                val wav = File(dir, name)
                if (wav.isFile && needsRepair(wav)) {
                    runCatching { WavWriter.repair(wav) }.onSuccess { repaired++ }
                }
            }
            // 混音写到一半被杀会留下 录音.wav.tmp：删掉，否则它既占空间又会混进归档
            dir.listFiles { file -> file.name.endsWith(".tmp") }?.forEach { it.delete() }
        }
        return repaired
    }

    /**
     * 头里的 data 长度与实际数据量不符就是没收尾。
     *
     * 只读文件头，不碰音频数据——启动路径要快。
     */
    fun needsRepair(wav: File): Boolean {
        if (wav.length() <= WavWriter.HEADER_SIZE) return false
        val header = ByteArray(WavWriter.HEADER_SIZE)
        val read = wav.inputStream().use { it.readNBytes(header, 0, header.size) }
        if (read != WavWriter.HEADER_SIZE) return false
        if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF") return false
        val declared = u32(header, 40).toLong() and 0xFFFF_FFFFL
        val actual = (wav.length() - WavWriter.HEADER_SIZE) and 0xFFFF_FFFFL
        return declared != actual
    }

    private fun u32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)
}
