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
 * 应用级设置。目前只有界面语言——会议与引擎配置由引擎负责，
 * 放在这里只会有两个真相。
 */
class AppSettings(private val store: KeyValueStore) {
    var language: AppLanguage
        get() = AppLanguage.fromTag(store.getString(KEY_LANGUAGE))
        set(value) = store.putString(KEY_LANGUAGE, value.tag)

    private companion object {
        const val KEY_LANGUAGE = "ui_language"
    }
}
