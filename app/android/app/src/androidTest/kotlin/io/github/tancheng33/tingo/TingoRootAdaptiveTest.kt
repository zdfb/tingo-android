package io.github.tancheng33.tingo

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tancheng33.tingo.ui.TingoRoot
import io.github.tancheng33.tingo.ui.theme.TingoTheme
import org.junit.Rule
import org.junit.Test

/**
 * 外壳的宽窄屏形态。
 *
 * 外壳把"是不是宽屏"当参数收下（由 MainActivity 用 WindowSizeClass 判断），
 * 所以这里直接按两种取值渲染，验证双栏与单栏各自的布局契约。
 */
class TingoRootAdaptiveTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun render(wide: Boolean) {
        val container = AppContainer(context)
        compose.setContent {
            TingoTheme {
                TingoRoot(container = container, wide = wide, onLanguageChange = {})
            }
        }
    }

    @Test
    fun wideScreenShowsListAndDetailSideBySide() {
        render(wide = true)
        // 双栏：右侧空态提示常驻，说明详情区已经并排存在
        compose.onNodeWithText(context.getString(R.string.detail_select_hint)).assertIsDisplayed()
        // 左侧列表仍在
        compose.onNodeWithText(context.getString(R.string.library_search_hint)).assertIsDisplayed()
        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit)
                .and(hasText(context.getString(R.string.library_title))),
        ).assertIsDisplayed()
    }

    @Test
    fun compactScreenShowsListOnly() {
        render(wide = false)
        compose.onNodeWithText(context.getString(R.string.library_search_hint)).assertIsDisplayed()
        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit)
                .and(hasText(context.getString(R.string.library_title))),
        ).assertIsDisplayed()
        // 单栏：没有并排的详情区，因此不该出现"从左侧选一场会议"
        compose.onNodeWithText(context.getString(R.string.detail_select_hint)).assertDoesNotExist()
    }
}
