package io.github.tancheng33.tingo.ui.detail

/**
 * 「录完 → 自动转写 → 自动写纪要」这条链的判断规则。
 *
 * 抽成纯函数是为了能测：写在这里的条件只要错一个字，用户就会在录完一场会后
 * 白白等一轮、或者干脆被覆盖掉旧纪要。
 */
/** 录制结束后那条自动链在界面层的状态：是不是这一场、启动过没有。 */
data class AutoChainFlags(
    /** 这一场是刚录完的：进页面就自动转写 */
    val transcribe: Boolean = false,
    val onTranscribeStarted: () -> Unit = {},
    /** 转写完接着写纪要（同一条链，只跑一次） */
    val minutes: Boolean = false,
    val onMinutesStarted: () -> Unit = {},
)

object AutoChain {

    /**
     * 转写完成后要不要接着写纪要。
     *
     * 四个条件缺一不可：
     * - 这条链是"刚录完"触发的（`armed`），手动转写不该顺手生成纪要；
     * - 转写真的成功了（失败时没有转写可写）；
     * - 这场会议还没有纪要（有就说明要么用户写过、要么重新生成过，不能覆盖）；
     * - 纪要状态是"待命"：NotConfigured 表示没配模型服务，自动开只会立刻失败。
     */
    fun shouldAutoStartMinutes(
        armed: Boolean,
        transcription: TranscriptionUiState,
        hasMinutes: Boolean,
        minutes: MinutesUiState,
    ): Boolean {
        if (!armed) return false
        if (transcription !is TranscriptionUiState.Done) return false
        if (hasMinutes) return false
        return minutes is MinutesUiState.Idle
    }
}
