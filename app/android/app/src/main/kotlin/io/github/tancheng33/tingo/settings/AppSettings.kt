package io.github.tancheng33.tingo.settings

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

    private companion object {
        const val KEY_LANGUAGE = "ui_language"
        const val KEY_LLM_BASE_URL = "llm_base_url"
        const val KEY_LLM_API_KEY = "llm_api_key"
        const val KEY_LLM_MODEL = "llm_model"
        const val KEY_LLM_PROTOCOL = "llm_protocol"
    }
}
