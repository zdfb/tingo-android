package io.github.tancheng33.tingo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tancheng33.tingo.settings.AppLanguage
import io.github.tancheng33.tingo.ui.homeStatusRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 首页（录制页）下方的"最近"与状态行来自真实容器：这条用例盯的是
 * "换了模型/没配服务时，界面拿到的状态是对的"，不依赖 Compose 渲染。
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun statusRowsReflectModelAndServiceState() {
        val container = AppContainer(context)
        container.installBundledModels()

        val rows = homeStatusRows(container)

        assertEquals(3, rows.size)
        // 内置模型解包成功时第一条必须是"已就绪"，否则说明状态判断与实际不符
        assertTrue("识别模型应显示已就绪：${rows[0].label}", rows[0].label.contains("就绪"))
        // 未配置服务时给人话，而不是一串空字段
        val llm = container.settings.llm
        if (!llm.isConfigured) {
            assertTrue("未配置时应明确提示：${rows[1].label}", rows[1].label.contains("未配置"))
        }
        assertTrue("版本号要露出来：${rows[2].label}", rows[2].label.startsWith("v"))
    }

    @Test
    fun engineLanguageMatchesAppSetting() {
        val container = AppContainer(context)

        val previous = container.settings.language
        container.settings.language = AppLanguage.EN
        assertEquals("en", container.uiLanguage())
        container.settings.language = AppLanguage.ZH
        assertEquals("zh", container.uiLanguage())
        container.settings.language = previous
    }
}
