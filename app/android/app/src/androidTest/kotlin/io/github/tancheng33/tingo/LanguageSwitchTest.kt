package io.github.tancheng33.tingo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import android.content.Context
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 应用内语言切换的设备级验证。
 *
 * 语言是资源维度的配置，改完必须重建 Activity 才生效——这里同时验证"切换后文案变了"
 * 与"重建（含冷启动语义的 attachBaseContext）后仍是英文"。
 */
class LanguageSwitchTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /**
     * 语言是持久化设置，上一次运行可能把它留在英文；清掉再重建，
     * 让每条用例都从"默认中文"开始，彼此不干扰。
     */
    @Before
    fun resetLanguage() {
        compose.activity.getSharedPreferences("tingo-settings", Context.MODE_PRIVATE).edit()
            .remove("ui_language").commit()
        compose.activityRule.scenario.recreate()
    }

    @Test
    fun switchingToEnglishUpdatesUiAndSurvivesRecreate() {
        val chineseSettings = compose.activity.getString(R.string.nav_settings)
        val chineseMeetings = compose.activity.getString(R.string.nav_meetings)
        val englishMeetings = "Meetings" // values-en/strings.xml 的 nav_meetings

        // 切到设置页
        compose.onNodeWithText(chineseSettings).performClick()
        // “English” 也出现在后面的纪要语言设置里；首个是应用语言切换项。
        compose.onAllNodesWithText("English")[0].performClick()

        // 重建后导航文案换成英文
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(englishMeetings).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(englishMeetings).assertIsDisplayed()
        compose.onNodeWithText(chineseMeetings).assertDoesNotExist()

        // 再重建一次（等价于返回键/配置变更后的重建），语言仍然是英文
        compose.activityRule.scenario.recreate()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(englishMeetings).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(englishMeetings).assertIsDisplayed()
    }
}
