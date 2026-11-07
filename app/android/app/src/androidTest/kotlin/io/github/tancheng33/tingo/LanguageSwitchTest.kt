package io.github.tancheng33.tingo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    @Test
    fun switchingToEnglishUpdatesUiAndSurvivesRecreate() {
        // 语言是持久化设置：上一次运行可能把它留在英文，测试不能假设初始是中文
        val chineseMeetings = "会议" // values/strings.xml 的 nav_meetings
        val englishMeetings = "Meetings" // values-en/strings.xml 的 nav_meetings

        // 先切到设置页，把语言复位成中文，再切英文——这样重复运行也不会互相影响
        // 底栏的标签就是导航文案本身（语言未知，所以两种都试）
        compose.onAllNodesWithText(compose.activity.getString(R.string.nav_settings))
            .fetchSemanticsNodes()
            .takeIf { it.isNotEmpty() }
            ?.let { compose.onNodeWithText(compose.activity.getString(R.string.nav_settings)).performClick() }
            ?: compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("中文").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(chineseMeetings).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("English").performClick()

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
