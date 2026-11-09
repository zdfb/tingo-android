package io.github.tancheng33.tingo.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertTrue

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

    @Test
    fun llmDefaultsToNothingConfigured() {
        val config = settings().llm
        assertEquals("", config.baseUrl)
        assertEquals("", config.apiKey)
        assertEquals("", config.model)
        assertTrue("默认必须不可用，逼用户自己填", !config.isConfigured)
    }

    @Test
    fun llmConfigIsPersistedAndTrimmed() {
        val store = FakeStore()
        settings(store).llm = LlmConfig(
            baseUrl = "  https://api.example.com  ",
            apiKey = " sk-1 ",
            model = " m ",
            protocol = LlmProtocol.ANTHROPIC,
        )
        val reloaded = settings(store).llm
        assertEquals("https://api.example.com", reloaded.baseUrl)
        assertEquals("sk-1", reloaded.apiKey)
        assertEquals("m", reloaded.model)
        assertEquals(LlmProtocol.ANTHROPIC, reloaded.protocol)
        assertTrue(reloaded.isConfigured)
    }

    @Test
    fun halfConfiguredLlmIsNotUsable() {
        val store = FakeStore()
        settings(store).llm = LlmConfig(baseUrl = "https://api.example.com", apiKey = "", model = "m")
        assertTrue("只有地址和模型、没有密钥时不能算配好", !settings(store).llm.isConfigured)
    }
}
