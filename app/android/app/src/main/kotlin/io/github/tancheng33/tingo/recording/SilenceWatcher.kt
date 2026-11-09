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
    /** 判断阈值：这一瞬间的峰值电平 ≥ 这个值就算有人说话 */
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
        return AutoStop(keepMs = lastVoiceMs + KEEP_AFTER_MS, minutes = silenceMinutes)
    }

    /** 开始一段新录音：清零，不要让上一场的"最后有声"影响这一场。 */
    fun reset() {
        lastVoiceMs = 0
    }

    /** @param keepMs 音频只保留到这一刻（毫秒）；null 表示整段都留 */
    data class AutoStop(val keepMs: Long?, val minutes: Int)

    companion object {
        /** 缺省 10 分钟（与桌面 `config::DEFAULT_SILENCE_MIN` 一致）；这里是唯一来源 */
        const val DEFAULT_MINUTES = 10

        /**
         * 说话的门限（峰值，0..1 满量程）。
         *
         * 桌面端用的是"5 秒窗口的 RMS 换算成分贝再映射到 0..1"（0.4 ≈ −40 dBFS），
         * 而 Android 端的电平是**每个缓冲区内的峰值**（见 `MicRecorder.peakLevel`）。
         * 两把尺子不能直接套同一个数：峰值 0.4 等于 −8 dBFS，正常说话根本到不了，
         * 会把正在开的会掐断。按峰值刻度，正常说话在 0.05~0.3，空调/键盘底噪低于 0.02，
         * 所以这里取 0.05（≈ −26 dBFS 峰值），与"抓系统声音是否有人声"用的是同一量级。
         */
        const val VOICE_LEVEL = 0.05f

        /** 与引擎 `autostop::KEEP_AFTER_S` 一致：最后有声之后再留 30 秒 */
        const val KEEP_AFTER_MS = 30_000L

        // 桌面每 5 秒检查一次；Android 每个 tick（200 ms）都会喂一次，判断本身是幂等的，
        // 所以不需要单独的定时器

    }
}
