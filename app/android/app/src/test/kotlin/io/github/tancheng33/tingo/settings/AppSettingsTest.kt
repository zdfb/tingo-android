package io.github.tancheng33.tingo.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {

    /** 内存实现：只验证偏好的读写与归一化逻辑，不涉及 Android 运行时。 */
    private class FakeStore(private val values: MutableMap<String, String> = mutableMapOf()) : KeyValueStore {
        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    private fun settings(store: FakeStore = FakeStore()) = AppSettings(store)

    @Test
    fun defaultsToChinese() {
        assertEquals(AppLanguage.ZH, settings().language)
    }

    @Test
    fun languageChoiceIsPersisted() {
        val store = FakeStore()
        settings(store).language = AppLanguage.EN
        // 用同一份存储重新构造，等价于下次冷启动
        assertEquals(AppLanguage.EN, settings(store).language)
    }

    @Test
    fun unknownStoredTagFallsBackToChinese() {
        assertEquals(AppLanguage.ZH, AppLanguage.fromTag("fr-FR"))
        assertEquals(AppLanguage.ZH, AppLanguage.fromTag(null))
    }

    @Test
    fun languageRoundTripsThroughTag() {
        assertEquals(AppLanguage.EN, AppLanguage.fromTag(AppLanguage.EN.tag))
        assertEquals(AppLanguage.ZH, AppLanguage.fromTag(AppLanguage.ZH.tag))
    }
}
