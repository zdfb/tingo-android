package io.github.tancheng33.tingo.recording

/**
 * 静音自动停录的判断。
 *
 * 桌面端每 5 秒看一眼两路音轨的电平，连续 N 分钟没人说话就结束录音，
 * 并且只保留到"最后有人说话之后 30 秒"——开会开到最后半小时没人出声时，
 * 保留那半小时的静音只会让文件变大、转写变慢。
 *
 * 这里只做判断，不碰文件：录音页每 tick 喂一次电平，到点返回一个"该停了"的结果。
 */
class SilenceWatcher(
    /** 连续多少分钟没人说话就停；0 表示不自动停（与桌面配置同一语义） */
    var silenceMinutes: Int = DEFAULT_MINUTES,
    /** 判断阈值：任一音轨电平 ≥ 这个值就算有人说话（与引擎 `autostop::VOICE_LEVEL` 一致） */
    private val voiceLevel: Float = VOICE_LEVEL,
) {

    /** 最后有人说话的时刻（毫秒，录音内时间）。0 表示还没听到过声音。 */
    var lastVoiceMs: Long = 0
        private set

    /**
     * 喂一次电平。
     *
     * @param level 0..1 的当前电平
     * @param elapsedMs 录音已经进行了多久（暂停不计）
     * @return 到点该自动停了返回那次"最后有声"的时刻，否则 null
     */
    fun observe(level: Float, elapsedMs: Long): AutoStop? {
        if (level >= voiceLevel) lastVoiceMs = elapsedMs
        if (silenceMinutes <= 0) return null
        val limit = silenceMinutes * 60_000L
        if (elapsedMs - lastVoiceMs < limit) return null
        return AutoStop(
            reason = Reason.SILENCE,
            keepMs = lastVoiceMs + KEEP_AFTER_MS,
            minutes = silenceMinutes,
        )
    }

    /** 开始一段新录音：清零，不要让上一场的"最后有声"影响这一场。 */
    fun reset() {
        lastVoiceMs = 0
    }

    /** 为什么停的（用户看到的提示文案不同）。 */
    enum class Reason { SILENCE }

    /** @param keepMs 音频只保留到这一刻（毫秒）；null 表示整段都留 */
    data class AutoStop(val reason: Reason, val keepMs: Long?, val minutes: Int)

    companion object {
        /** 与桌面 `config::DEFAULT_SILENCE_MIN` 一致 */
        const val DEFAULT_MINUTES = 10

        /** 与引擎 `autostop::VOICE_LEVEL` 一致：0.4 ≈ -40 dB */
        const val VOICE_LEVEL = 0.4f

        /** 与引擎 `autostop::KEEP_AFTER_S` 一致：最后有声之后再留 30 秒 */
        const val KEEP_AFTER_MS = 30_000L

        /** 检查间隔：桌面每 5 秒一次 */
        const val CHECK_MS = 5_000L
    }
}
