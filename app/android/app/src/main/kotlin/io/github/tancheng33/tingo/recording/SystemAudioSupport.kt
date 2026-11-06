package io.github.tancheng33.tingo.recording

import android.os.Build

/**
 * 能不能录系统声音：把判断从界面里拿出来，才有办法写测试。
 *
 * 三种"不行"要给出三句不同的话——用户能据此知道该换手机、该给权限，
 * 还是该放弃这条路改用外放。
 */
sealed interface SystemAudioSupport {
    /** 可以尝试：系统版本够、用户给了授权 */
    data object Available : SystemAudioSupport

    /** 系统太老（Android 10 以下没有 AudioPlaybackCapture） */
    data object UnsupportedOs : SystemAudioSupport

    /** 用户拒绝了屏幕录制授权 */
    data object PermissionDenied : SystemAudioSupport

    /** 系统版本够，但用户没启用这个开关 */
    data object NotRequested : SystemAudioSupport

    companion object {
        /** AudioPlaybackCapture 从 Android 10（API 29）开始提供。 */
        const val MIN_SDK = Build.VERSION_CODES.Q

        /**
         * @param requested 用户是否在录音页打开了"同时录会议软件的声音"
         * @param granted 屏幕录制授权是否拿到
         * @param sdk 当前设备的 API 级别
         */
        fun decide(requested: Boolean, granted: Boolean, sdk: Int = Build.VERSION.SDK_INT): SystemAudioSupport = when {
            !requested -> NotRequested
            sdk < MIN_SDK -> UnsupportedOs
            !granted -> PermissionDenied
            else -> Available
        }

        /** 只有 [Available] 才真的去开捕获。 */
        fun SystemAudioSupport.canCapture(): Boolean = this == Available
    }
}
