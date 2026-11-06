package io.github.tancheng33.tingo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tancheng33.tingo.data.MeetingCard
import io.github.tancheng33.tingo.ui.library.LibraryScreen
import io.github.tancheng33.tingo.ui.library.LibraryUiState
import io.github.tancheng33.tingo.ui.theme.TingoTheme
import org.junit.Rule
import org.junit.Test

/** 会议列表的界面契约：显示什么、标记什么、点了回调什么。 */
class LibraryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** 期望文案从资源取：模拟器/CI 的系统语言不一定是中文，写死中文会假失败。 */
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int): String = context.getString(id)

    private fun card(
        id: String,
        title: String,
        date: String = "2026-10-28",
        unreadable: Boolean = false,
        archived: Boolean = false,
    ) = MeetingCard(
        id = id,
        title = title,
        date = date,
        time = "14:30",
        duration = "02:00",
        oneLiner = "一句话总结",
        tags = emptyList(),
        archived = archived,
        hasAudio = true,
        hasMinutes = true,
        unreadable = unreadable,
    )

    private fun render(state: LibraryUiState, onOpen: (String) -> Unit = {}) {
        compose.setContent {
            TingoTheme {
                LibraryScreen(
                    state = state,
                    onOpenMeeting = onOpen,
                    onRefresh = {},
                    onQueryChange = {},
                    onArchivedOnlyChange = {},
                )
            }
        }
    }

    @Test
    fun showsMeetingTitleAndCount() {
        render(
            LibraryUiState.Content(
                items = listOf(card("a", "官网注册转化复盘")),
                totalCount = 1,
                unreadableCount = 0,
            ),
        )
        compose.onNodeWithText("官网注册转化复盘").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_count).format(1)).assertIsDisplayed()
    }

    @Test
    fun marksUnreadableMeeting() {
        render(
            LibraryUiState.Content(
                items = listOf(card("broken", "坏掉的会议", unreadable = true)),
                totalCount = 1,
                unreadableCount = 1,
            ),
        )
        compose.onNodeWithText(text(R.string.library_unreadable)).assertIsDisplayed()
    }

    @Test
    fun opensMeetingOnClick() {
        var opened: String? = null
        render(
            LibraryUiState.Content(
                items = listOf(card("2026-10-28_1430_官网注册转化复盘", "官网注册转化复盘")),
                totalCount = 1,
                unreadableCount = 0,
            ),
            onOpen = { opened = it },
        )
        compose.onNodeWithText("官网注册转化复盘").performClick()
        assert(opened == "2026-10-28_1430_官网注册转化复盘")
    }

    @Test
    fun emptyLibraryExplainsWhatToDo() {
        render(LibraryUiState.Empty)
        compose.onNodeWithText(text(R.string.library_empty)).assertIsDisplayed()
    }
}
