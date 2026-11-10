package io.github.tancheng33.tingo.settings

import io.github.tancheng33.tingo.recording.SilenceWatcher

import android.content.Context

/** 界面语言。取值直接存成 BCP-47 tag，方便将来加语言。 */
enum class AppLanguage(val tag: String) {
    ZH("zh-CN"),
    EN("en"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: ZH
    }
}

/**
 * 键值存储端口。
 *
 * 把 SharedPreferences 挡在接口后面：偏好的读写逻辑（默认值、语言 tag 归一化）
 * 因此可以在 JVM 单测里直接跑，不必拉起 Android 运行时。
 */
interface KeyValueStore {
    fun getString(key: String): String?

    fun putString(key: String, value: String)
}

/** Android 实现：应用私有的 SharedPreferences。 */
class SharedPrefsStore(context: Context) : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    private companion object {
        const val FILE = "tingo-settings"
    }
}

/**
 * 应用级设置：界面语言 + 纪要用的模型服务。
 *
 * 会议与引擎配置仍由引擎负责，放在这里只会有两个真相。
 */
class AppSettings(private val store: KeyValueStore) {
    var language: AppLanguage
        get() = AppLanguage.fromTag(store.getString(KEY_LANGUAGE))
        set(value) = store.putString(KEY_LANGUAGE, value.tag)

    /** 模型服务配置；默认全空，由用户自己填。 */
    var llm: LlmConfig
        get() = LlmConfig(
            baseUrl = store.getString(KEY_LLM_BASE_URL).orEmpty(),
            apiKey = store.getString(KEY_LLM_API_KEY).orEmpty(),
            model = store.getString(KEY_LLM_MODEL).orEmpty(),
            protocol = LlmProtocol.fromWire(store.getString(KEY_LLM_PROTOCOL)),
        )
        set(value) {
            store.putString(KEY_LLM_BASE_URL, value.baseUrl.trim())
            store.putString(KEY_LLM_API_KEY, value.apiKey.trim())
            store.putString(KEY_LLM_MODEL, value.model.trim())
            store.putString(KEY_LLM_PROTOCOL, value.protocol.wire)
        }

    /**
     * 连续多少分钟没人说话就自动结束录音；0 表示不自动结束（与桌面 `[record] auto_stop_silence_min` 一致）。
     */
    var autoStopSilenceMin: Int
        get() = store.getString(KEY_AUTO_STOP)?.toIntOrNull() ?: SilenceWatcher.DEFAULT_MINUTES
        set(value) = store.putString(KEY_AUTO_STOP, value.coerceIn(0, MAX_SILENCE_MIN).toString())

    /**
     * 新手引导走完了没有。
     *
     * 桌面端记在 localStorage；这里记在应用私有偏好里。没有这个键 = 没走过（新装的用户），
     * 所以缺省按 false 处理，与桌面一致。真正的"别拦人"判断在
     * `OnboardingViewModel.shouldOnboard`：库里已经有会议的老用户不该被拦。
     */
    var onboarded: Boolean
        get() = store.getString(KEY_ONBOARDED) == "1"
        set(value) = store.putString(KEY_ONBOARDED, if (value) "1" else "0")

    /**
     * 引导走到第几步（0 起）。
     *
     * 桌面端从"设置 → 回到新手引导"会直接跳回**大模型**那一步（`#/welcome/llm`），
     * 而不是从第一步重走；手机上同理——用户是去填 API Key 的，回来还得重看四屏是折磨。
     */
    var onboardStep: Int
        get() = (store.getString(KEY_ONBOARD_STEP)?.toIntOrNull() ?: 0).coerceIn(0, MAX_ONBOARD_STEP)
        set(value) = store.putString(KEY_ONBOARD_STEP, value.coerceIn(0, MAX_ONBOARD_STEP).toString())

    private companion object {
        const val KEY_ONBOARD_STEP = "onboard_step"
        /** 引导一共 5 步（与 `OnboardingViewModel.Step` 对齐）；越界值一律夹回去 */
        const val MAX_ONBOARD_STEP = 4
        const val KEY_AUTO_STOP = "auto_stop_silence_min"
        const val MAX_SILENCE_MIN = 60
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_LANGUAGE = "ui_language"
        const val KEY_LLM_BASE_URL = "llm_base_url"
        const val KEY_LLM_API_KEY = "llm_api_key"
        const val KEY_LLM_MODEL = "llm_model"
        const val KEY_LLM_PROTOCOL = "llm_protocol"
    }
}
