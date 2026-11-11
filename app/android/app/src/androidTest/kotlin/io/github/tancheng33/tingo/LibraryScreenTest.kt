package io.github.tancheng33.tingo

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tancheng33.tingo.data.MeetingCard
import io.github.tancheng33.tingo.ui.library.LibraryScreen
import io.github.tancheng33.tingo.ui.library.LibraryManageActions
import io.github.tancheng33.tingo.ui.library.LibraryUiState
import io.github.tancheng33.tingo.ui.theme.TingoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LibraryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun card(title: String, unreadable: Boolean = false) = MeetingCard(
        id = "meeting-id",
        title = title,
        date = "2026-10-28",
        time = "14:30",
        duration = "02:00",
        oneLiner = "一句话总结",
        tags = emptyList(),
        archived = false,
        hasAudio = true,
        hasMinutes = true,
        unreadable = unreadable,
    )

    private fun render(state: LibraryUiState, onOpenMeeting: (String) -> Unit = {}) {
        compose.setContent {
            TingoTheme {
                LibraryScreen(
                    state = state,
                    onOpenMeeting = onOpenMeeting,
                    onRefresh = {},
                    onQueryChange = {},
                    onArchivedOnlyChange = {},
                )
            }
        }
    }

    @Test
    fun showsMeetingTitleAndCount() {
        render(LibraryUiState.Content(items = listOf(card("官网注册转化复盘")), totalCount = 1, unreadableCount = 0))
        compose.onNodeWithText("官网注册转化复盘").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.library_count, 1)).assertIsDisplayed()
    }

    @Test
    fun marksUnreadableMeeting() {
        render(LibraryUiState.Content(items = listOf(card("坏掉的会议", unreadable = true)), totalCount = 1, unreadableCount = 1))
        compose.onNodeWithText(context.getString(R.string.library_unreadable)).assertIsDisplayed()
    }

    @Test
    fun opensMeetingOnClick() {
        var opened: String? = null
        render(
            LibraryUiState.Content(items = listOf(card("官网注册转化复盘")), totalCount = 1, unreadableCount = 0),
            onOpenMeeting = { opened = it },
        )
        compose.onNodeWithText("官网注册转化复盘").performClick()
        compose.runOnIdle { assertEquals("meeting-id", opened) }
    }

    @Test
    fun emptyLibraryKeepsItsHeadingSearchAndStartAction() {
        var startCount = 0

        compose.setContent {
            TingoTheme {
                LibraryScreen(
                    state = LibraryUiState.Empty,
                    onOpenMeeting = {},
                    onRefresh = {},
                    onQueryChange = {},
                    onArchivedOnlyChange = {},
                    onStartRecording = { startCount++ },
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.library_title))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
            .assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.library_search_hint)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.library_start_recording)).performClick()
        compose.runOnIdle { assertEquals(1, startCount) }
    }

    @Test
    fun emptySearchExplainsTheResultAndCanClearTheQuery() {
        var clearedQuery = false
        compose.setContent {
            TingoTheme {
                LibraryScreen(
                    state = LibraryUiState.Content(
                        items = emptyList(),
                        totalCount = 3,
                        unreadableCount = 0,
                        query = "missing term",
                    ),
                    onOpenMeeting = {},
                    onRefresh = {},
                    onQueryChange = { clearedQuery = it.isEmpty() },
                    onArchivedOnlyChange = {},
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.library_no_results)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.library_clear_filters)).performClick()
        compose.runOnIdle { assertEquals(true, clearedQuery) }
    }

    @Test
    fun trashHasAnAccessibleTitleAndReturnsToMeetings() {
        var backCount = 0
        compose.setContent {
            TingoTheme {
                LibraryScreen(
                    state = LibraryUiState.Empty,
                    onOpenMeeting = {},
                    onRefresh = {},
                    onQueryChange = {},
                    onArchivedOnlyChange = {},
                    manage = LibraryManageActions(onShowMeetings = { backCount++ }),
                    trashVisible = true,
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.library_trash_title))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_back)).performClick()
        compose.runOnIdle { assertEquals(1, backCount) }
    }
}
