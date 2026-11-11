package io.github.tancheng33.tingo

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tancheng33.tingo.ui.record.RecordScreen
import io.github.tancheng33.tingo.ui.record.RecordUiState
import io.github.tancheng33.tingo.ui.theme.TingoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RecordScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun idleScreenHasAccessiblePageTitleAndStartsRecording() {
        var startCount = 0

        compose.setContent {
            TingoTheme {
                RecordScreen(
                    state = RecordUiState.Idle,
                    onStart = { startCount++ },
                    onPause = {},
                    onResume = {},
                    onStop = {},
                    onDiscard = {},
                    onOpenMeeting = {},
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.nav_record))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.record_start))
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertEquals(1, startCount) }
    }

    @Test
    fun recordingStateShowsElapsedTimeAndKeepsPauseAndFinishActions() {
        var pauseCount = 0
        var stopCount = 0

        compose.setContent {
            TingoTheme {
                RecordScreen(
                    state = RecordUiState.Recording(elapsedMs = 65_000, level = 0.4f),
                    onStart = {},
                    onPause = { pauseCount++ },
                    onResume = {},
                    onStop = { stopCount++ },
                    onDiscard = {},
                    onOpenMeeting = {},
                    liveLines = listOf("A sentence from the meeting"),
                )
            }
        }

        compose.onNodeWithText("01:05").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.record_live_hint)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.record_pause)).performClick()
        compose.onNodeWithText(context.getString(R.string.record_stop)).performClick()
        compose.runOnIdle {
            assertEquals(1, pauseCount)
            assertEquals(1, stopCount)
        }
    }
}
